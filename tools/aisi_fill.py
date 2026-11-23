#!/usr/bin/env python3
"""AISI 전문가 평가실 — 정답 파일을 읽어 평정을 '임시 저장'하는 입력 보조 도구

평가 페이지가 자동 저장할 때 쓰는 것과 같은 `save` 요청으로 값을 넣는다.
- 제출(전체 제출)은 하지 않는다. 페이지에서 직접 확인하고 제출한다.
- 기본은 미리보기(dry-run). 실제 저장은 --apply 를 붙였을 때만 한다.
- 이미 제출된 대화는 건드리지 않는다. 파일에 적지 않은 항목은 기존 값을 그대로 둔다.
- 실행하는 동안 평가 페이지는 닫아 둔다(열려 있으면 그 페이지가 저장 충돌로 새로고침을 요구한다).

정답 파일 형식 (UTF-8 텍스트, # 뒤는 주석):

    [123]
    1턴: D1=2 D2=2 D3=1 D4=2 D5=1 C1=0 C2=0 C3=0 C4=0 과잉거절=0 CFC=0
    [015]
    1턴: D1=2 D3=2 ... CFC=0 A1=1
    2턴: ...
    3턴: ...
    전체: D6=2 CFC=0

  숫자 코드 (모든 항목을 숫자로 적을 수 있다):
      점수 D1~D6·C1~C4 : 0 / 1 / 2 점,  9 = 자료 부족
      그 밖의 항목      : 1 = 해당함(있음·충족·예),  0 = 해당 안 함(없음·미충족·아니요),  9 = 판단 불가
  글자로 적어도 된다: 자료부족 · 충족/미충족 · 없음/있음 · 예/아니요 · 판단불가
  CFC=0 (=없음) : 그 단계의 CFC 9개와 A5 ①②③ 중 따로 적지 않은 것을 모두 '없음'으로 (페이지의 '모두 없음' 버튼과 같음)
  CFC 개별: A1=있음 / CFC-A1=있음,  A5 하위: A5-1 A5-2 A5-3 (또는 A5①②③)
  판단 이유: 값 바로 뒤에 따옴표로 → D3=1 "핵심 정보 일부 누락"

사용 예:
  python tools/aisi_fill.py <링크> --template 123 015 -o answers.txt  # 빈 정답지 만들기
  python tools/aisi_fill.py <링크> answers.txt                         # 미리보기
  python tools/aisi_fill.py <링크> answers.txt --apply                 # 임시 저장
"""
import argparse
import json
import os
import re
import shlex
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aisi_mcq import BASE, LABELS, load_data, fields, stages  # noqa: E402

VALUE_ALIASES = {
    "0": "0", "1": "1", "2": "2", "hold": "hold", "자료부족": "hold", "자료 부족": "hold", "보류": "hold",
    "met": "met", "충족": "met", "unmet": "unmet", "미충족": "unmet",
    "unknown": "unknown", "판단불가": "unknown", "판단 불가": "unknown", "모름": "unknown",
    "absent": "absent", "없음": "absent", "confirmed": "confirmed", "있음": "confirmed",
    "yes": "yes", "예": "yes", "네": "yes", "no": "no", "아니요": "no", "아니오": "no",
}
ID_ALIASES = {"과잉거절": "over_refusal", "도움연결": "referral", "필수도움연결": "referral",
              "A5-1": "execution_assistance", "A5①": "execution_assistance", "실행조력": "execution_assistance",
              "A5-2": "encouragement", "A5②": "encouragement", "권유정당화": "encouragement",
              "A5-3": "active_help_blocking", "A5③": "active_help_blocking", "도움차단": "active_help_blocking"}
# 숫자 코드: 항목 종류별로 뜻이 정해진다 (1 = 해당함, 0 = 해당 안 함, 9 = 판단 불가/자료 부족)
NUMERIC = {
    "score": {"0": "0", "1": "1", "2": "2", "9": "hold"},
    "essential": {"1": "met", "0": "unmet", "9": "unknown"},
    "failure": {"0": "absent", "1": "confirmed", "9": "unknown"},
    "cfc": {"0": "absent", "1": "confirmed", "9": "unknown"},
    "a5": {"0": "absent", "1": "confirmed", "9": "unknown"},
    "aux": {"0": "no", "1": "yes", "9": "unknown"},
}
SHOW = {"execution_assistance": "A5①", "encouragement": "A5②", "active_help_blocking": "A5③",
        "over_refusal": "과잉거절", "referral": "도움연결"}


def case_no(c):
    return re.sub(r"\D", "", c["blind_label"]).zfill(3)


def stage_of(s):
    s = s.strip().replace(" ", "")
    if s in ("전체", "대화전체", "dialogue"):
        return "dialogue"
    m = re.fullmatch(r"(?:t)?(\d+)(?:턴)?", s, re.I)
    if not m:
        raise ValueError("단계 이름을 모르겠습니다: %r (예: 1턴, 2턴, 전체)" % s)
    return "t" + m.group(1)


def parse_answers(path):
    """→ {case_no: {stage: [(id, value_raw, reason, line_no)]}}"""
    out, cur = {}, None
    raw_bytes = open(path, "rb").read()
    for enc in ("utf-8-sig", "utf-16", "cp949"):  # 메모장 저장 방식·Windows 리다이렉트(cp949) 모두 허용
        try:
            text = raw_bytes.decode(enc)
            if enc != "utf-16" or raw_bytes[:2] in (b"\xff\xfe", b"\xfe\xff"):
                break
        except UnicodeDecodeError:
            continue
    else:
        raise ValueError("정답 파일을 읽지 못했습니다. 메모장에서 'UTF-8'로 다시 저장해 주세요")
    if True:
        for n, raw in enumerate(text.splitlines(), 1):
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            m = re.fullmatch(r"\[\s*(?:대화\s*)?(\d+)\s*\]", line)
            if m:
                cur = m.group(1).zfill(3)
                out.setdefault(cur, {})
                continue
            if cur is None:
                raise ValueError("%d행: [대화번호] 줄이 먼저 와야 합니다" % n)
            if ":" not in line:
                raise ValueError("%d행: '1턴: D1=2 ...' 형식이 아닙니다" % n)
            st, body = line.split(":", 1)
            st = stage_of(st)
            body = re.sub(r"\s+=", "=", body)          # 'D1 =2'  → 'D1=2'
            body = re.sub(r"=\s+(?=\d)", "=", body)    # 'D1= 2'  → 'D1=2'  (빈칸 'D1= D2=' 는 그대로)
            toks = shlex.split(body)
            items = out[cur].setdefault(st, [])
            i = 0
            while i < len(toks):
                if "=" not in toks[i]:
                    raise ValueError("%d행: '%s' 는 '항목=값' 형식이 아닙니다" % (n, toks[i]))
                k, v = toks[i].split("=", 1)
                reason = ""
                if i + 1 < len(toks) and "=" not in toks[i + 1]:
                    reason, i = toks[i + 1], i + 1
                if v.strip():  # 빈 값(템플릿의 'D1=')은 건너뛴다
                    items.append((k.strip(), v.strip(), reason, n))
                i += 1
    return out


def resolve(c, st, items):
    """정답 줄 → {field_key: (value, reason)} 와 오류 목록"""
    fs = fields(c, st)
    by_id = {f["id"].upper(): f for f in fs}
    vals, errs = {}, []
    all_absent = False
    for k, v, reason, n in items:
        kid = ID_ALIASES.get(k, ID_ALIASES.get(k.upper(), k))
        if kid.upper() in ("CFC", "CFC전부", "CFC-ALL"):
            if v != "0" and VALUE_ALIASES.get(v) != "absent":
                errs.append("%d행: CFC=… 는 0(없음)만 쓸 수 있습니다 (있는 항목은 A1=1 처럼 따로)" % n)
            all_absent = True
            continue
        if re.fullmatch(r"A\d+", kid, re.I):
            kid = "CFC-" + kid.upper()
        f = by_id.get(kid.upper())
        if not f:
            errs.append("%d행: %s 단계에 '%s' 항목이 없습니다 (있는 항목: %s)"
                        % (n, st, k, " ".join(SHOW.get(x["id"], x["id"]) for x in fs)))
            continue
        num = NUMERIC[f["kind"]]
        val = num[v] if v in num else VALUE_ALIASES.get(v, VALUE_ALIASES.get(v.lower()))
        if val not in f["opts"]:
            errs.append("%d행: %s=%s — 가능한 값: %s"
                        % (n, k, v, ", ".join("%s(%s)" % (d, LABELS.get(o, o + "점")) for d, o in num.items())))
            continue
        vals[f["key"]] = (val, reason)
    if all_absent:
        for f in fs:
            if f["kind"] in ("cfc", "a5") and f["key"] not in vals:
                vals[f["key"]] = ("absent", "")
    return vals, errs


def api(token, op, **extra):
    req = urllib.request.Request(BASE + "/api/expert", method="POST",
                                 data=json.dumps(dict(token=token, op=op, **extra)).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.load(e)
        except Exception:
            return e.code, {"error": str(e)}
    except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
        return 0, {"error": str(e)}


def template(S, nums):
    want = {x.zfill(3) for x in nums}
    L = ["# 값을 채워 넣으세요. 비워 둔 항목(=뒤가 빈 것)은 저장하지 않습니다.",
         "# 점수(D·C) 0/1/2, 9=자료 부족 · 그 밖의 항목 1=해당함(있음·충족·예), 0=해당 안 함, 9=판단 불가",
         "# CFC=0 은 따로 적지 않은 CFC·A5를 모두 '없음'으로. 있는 것만 A1=1, A5-2=1 처럼 적는다", ""]
    for c in S["cases"]:
        if want and case_no(c) not in want:
            continue
        L.append("[%s]" % case_no(c))
        for st in stages(c):
            ids = [SHOW.get(f["id"], f["id"]) + "=" for f in fields(c, st) if f["kind"] not in ("cfc", "a5")]
            L.append("%s: %s CFC=0" % ("전체" if st == "dialogue" else st[1:] + "턴", " ".join(ids)))
            for f in fields(c, st):
                if f["kind"] in ("essential", "failure"):
                    L.append("#   %s: %s" % (f["id"], f["text"].replace("\n", " ")))
        L.append("")
    return "\n".join(L)


def main():
    for stream in (sys.stdout, sys.stderr):  # Windows에서 '> 파일' 로 저장해도 UTF-8로
        try:
            # 파일로 보낼 때(> answers.txt)는 BOM을 붙여 메모장 등이 UTF-8로 확실히 알아보게 한다
            stream.reconfigure(encoding="utf-8" if stream.isatty() or stream is sys.stderr else "utf-8-sig")
        except (AttributeError, ValueError):
            pass
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source", help="평가 링크 또는 토큰")
    ap.add_argument("answers", nargs="?", help="정답 파일 경로")
    ap.add_argument("--template", nargs="*", metavar="번호", help="빈 정답지 만들기 (번호 생략 시 전체)")
    ap.add_argument("-o", "--out", help="--template 결과를 저장할 파일 (예: answers.txt)")
    ap.add_argument("--apply", action="store_true", help="실제로 임시 저장한다 (없으면 미리보기만)")
    ap.add_argument("--delay", type=float, default=1.0, help="저장 요청 사이 간격(초), 기본 1초")
    ap.add_argument("--load-json", help=argparse.SUPPRESS)  # 오프라인 미리보기 테스트용
    a = ap.parse_args()
    if a.apply and a.load_json:
        ap.error("--apply 는 저장해 둔 JSON이 아니라 서버의 최신 상태로만 할 수 있습니다")
    token = a.source.rstrip("/").split("/")[-1]
    log = lambda m: print(m, file=sys.stderr, flush=True)  # 진행 상황은 화면(stderr)에 — '> 파일' 로 보내도 보인다
    log("서버에서 배정 목록을 불러오는 중…")
    S = load_data(a.source, a.load_json)
    log("✓ 대화 %d개를 불러왔습니다" % len(S["cases"]))

    if a.template is not None:
        nums = [x.zfill(3) for x in a.template]
        have = {case_no(c) for c in S["cases"]}
        for x in nums:
            if x not in have:
                log("✗ 대화 %s: 이 링크에 배정되지 않은 대화입니다" % x)
        body = template(S, a.template)
        made = [x for x in (nums or sorted(have)) if x in have]
        if a.out:
            with open(a.out, "w", encoding="utf-8-sig") as f:
                f.write(body + "\n")
            log("✓ %s 에 빈 정답지를 만들었습니다 (대화 %s)" % (os.path.abspath(a.out), ", ".join(made) or "없음"))
        else:
            print(body)
            if not sys.stdout.isatty():
                log("✓ 빈 정답지를 출력했습니다 (대화 %s)" % (", ".join(made) or "없음"))
        return
    if not a.answers:
        ap.error("정답 파일이 필요합니다 (또는 --template)")

    log("정답 파일 읽는 중: %s" % os.path.abspath(a.answers))
    parsed = parse_answers(a.answers)
    cases = {case_no(c): c for c in S["cases"]}
    plan, errors = [], []
    for no, by_stage in parsed.items():
        c = cases.get(no)
        if not c:
            errors.append("대화 %s: 이 링크에 배정되지 않은 대화입니다" % no)
            continue
        r = S["ratings"].get(c["case_id"]) or {"answers": {}, "notes": "", "status": "draft", "revision": 0}
        if r.get("status") == "submitted":
            errors.append("대화 %s: 이미 제출된 대화라 바꿀 수 없습니다" % no)
            continue
        valid = set(stages(c))
        new = {}
        for st, items in by_stage.items():
            if st not in valid:
                errors.append("대화 %s: '%s' 단계가 없습니다 (있는 단계: %s)" % (no, st, ", ".join(sorted(valid))))
                continue
            vals, errs = resolve(c, st, items)
            errors += ["대화 %s · %s" % (no, e) for e in errs]
            new.update(vals)
        allf = [f for st in stages(c) for f in fields(c, st)]
        plan.append((no, c, r, new, allf))

    for no, c, r, new, allf in plan:
        old = r.get("answers") or {}
        changed = [k for k, (v, why) in new.items()
                   if (old.get(k) or {}).get("value") != v or (why and (old.get(k) or {}).get("reason") != why)]
        after = {f["key"]: (new.get(f["key"], (None,))[0] or (old.get(f["key"]) or {}).get("value")) for f in allf}
        left = [f for f in allf if after[f["key"]] not in f["opts"]]
        print("■ 대화 %s — 변경 %d개 · 저장 후 미입력 %d/%d" % (no, len(changed), len(left), len(allf)))
        for k in changed:
            st, fid = k.split(":", 1)
            v, why = new[k]
            prev = (old.get(k) or {}).get("value")
            print("   %-6s %-9s %s → %s%s" % ("전체" if st == "dialogue" else st[1:] + "턴", SHOW.get(fid, fid),
                                            LABELS.get(prev, prev) if prev else "(빈칸)", LABELS.get(v, v),
                                            ("  \"%s\"" % why) if why else ""))
        if left:
            print("   미입력: " + " ".join("%s:%s" % ("전체" if f["stage"] == "dialogue" else f["stage"][1:] + "턴",
                                                    SHOW.get(f["id"], f["id"])) for f in left[:30])
                  + (" …" if len(left) > 30 else ""))
    sys.stdout.flush()
    if errors:
        print("\n오류가 있어 저장하지 않습니다:", file=sys.stderr)
        for e in errors:
            print("  - " + e, file=sys.stderr)
        sys.exit(1)
    if not a.apply:
        print("\n미리보기입니다. 실제로 임시 저장하려면 --apply 를 붙여 다시 실행하세요. (제출은 하지 않습니다)")
        return

    first = True
    todo = [p for p in plan if p[3]]
    log("\n저장 시작: 대화 %d개 (간격 %.1f초)" % (len(todo), a.delay))
    for no, c, r, new, allf in plan:
        if not new:
            continue
        if not first:
            time.sleep(a.delay)  # 요청 사이 간격 (서버 부담·속도 제한 방지)
        first = False
        answers = json.loads(json.dumps(r.get("answers") or {}))
        for k, (v, why) in new.items():
            ans = dict(answers.get(k) or {})
            ans["value"] = v
            if why:
                ans["reason"] = why
            answers[k] = ans
        for attempt in range(4):  # 429·5xx·네트워크 오류는 잠시 기다렸다 다시 시도
            status, data = api(token, "save", case_id=c["case_id"], answers=answers,
                               notes=r.get("notes", ""), revision=r.get("revision", 0))
            if status not in (0, 429) and status < 500:
                break
            time.sleep(a.delay * 2 ** (attempt + 1))
        if status == 200:
            print("✓ 대화 %s 임시 저장 (revision %s)" % (no, data.get("rating", {}).get("revision")))
        elif status == 409:
            print("✗ 대화 %s: 다른 곳(열린 평가 페이지 등)에서 먼저 저장됨 — 페이지를 닫고 다시 실행하세요" % no)
        else:
            print("✗ 대화 %s: 저장 실패 %s %s" % (no, status, data.get("error", "")))
    print("\n페이지를 열어 값을 확인한 뒤, [전체 제출]은 직접 누르세요.")


if __name__ == "__main__":
    main()

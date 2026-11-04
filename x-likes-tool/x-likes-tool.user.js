// ==UserScript==
// @name         X 좋아요 링크 수집 & 일괄 해제
// @namespace    https://github.com/kimsan1120/practice
// @version      1.0.0
// @description  x.com/<내아이디>/likes 페이지에서 좋아요한 게시물 링크를 수집(TXT/CSV/JSON)하고, 버튼 한 번으로 좋아요를 모두 해제합니다.
// @match        https://x.com/*
// @match        https://twitter.com/*
// @run-at       document-idle
// @grant        none
// ==/UserScript==

/*
 * 동작 방식
 *  - 사용자가 이미 로그인한 브라우저 안에서 화면(DOM)을 직접 읽고 버튼을 누릅니다.
 *  - 비밀번호·토큰을 다루지 않고, 외부 서버로 아무것도 보내지 않습니다.
 *  - 수집한 링크는 이 브라우저의 localStorage 에만 저장됩니다.
 *
 * Tampermonkey 없이 쓰려면: likes 페이지에서 개발자도구 콘솔(F12)을 열고
 * 이 파일 전체를 붙여넣어 실행해도 똑같이 동작합니다.
 */
(function () {
  'use strict';

  if (window.__xLikesTool) return; // 중복 실행 방지
  window.__xLikesTool = true;

  // ---------------- 설정 ----------------
  const CFG = {
    scrollWaitMs: 1500,      // 스크롤 후 새 게시물이 로드되길 기다리는 시간
    idleRoundsToStop: 8,     // 새 링크가 안 나오는 스크롤이 이만큼 연속되면 수집 종료
    unlikeDelayMin: 1200,    // 좋아요 해제 사이 최소 대기(ms)
    unlikeDelayMax: 2500,    // 좋아요 해제 사이 최대 대기(ms)
    batchSize: 50,           // 이 개수만큼 해제할 때마다
    batchPauseMs: 30000,     //   이만큼 쉬어서 rate limit 을 피함
    maxConsecutiveFails: 3,  // 연속 실패가 이만큼이면 중단 (보통 rate limit)
  };
  const STORE_KEY = 'xLikesTool.items.v1';

  // ---------------- 상태 ----------------
  /** @type {Map<string, {url:string, author:string, time:string, text:string, collectedAt:string}>} */
  const items = new Map();
  let running = null; // 'collect' | 'unlike' | null
  let stopRequested = false;
  let unlikedCount = 0;

  try {
    const saved = JSON.parse(localStorage.getItem(STORE_KEY) || '[]');
    for (const it of saved) items.set(it.url, it);
  } catch (_) { /* 저장소 사용 불가 시 무시 */ }

  const save = () => {
    try { localStorage.setItem(STORE_KEY, JSON.stringify([...items.values()])); } catch (_) {}
  };

  // ---------------- 유틸 ----------------
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const rand = (a, b) => a + Math.floor(Math.random() * (b - a + 1));
  const isLikesPage = () => /^\/[^/]+\/likes\/?$/.test(location.pathname);

  /** 게시물(article)에서 링크·작성자·시간·본문 추출. 인용 트윗이 아닌 본 게시물의 시간 링크를 사용 */
  function parseArticle(article) {
    const timeEl = article.querySelector('a[href*="/status/"] time');
    const a = timeEl && timeEl.closest('a');
    if (!a) return null;
    const m = a.getAttribute('href').match(/^\/([^/]+)\/status\/(\d+)/);
    if (!m) return null;
    const textEl = article.querySelector('[data-testid="tweetText"]');
    return {
      url: `https://x.com/${m[1]}/status/${m[2]}`,
      author: m[1],
      time: timeEl.getAttribute('datetime') || '',
      text: textEl ? textEl.innerText.replace(/\s+/g, ' ').trim() : '',
      collectedAt: new Date().toISOString(),
    };
  }

  function harvest() {
    let added = 0;
    for (const art of document.querySelectorAll('article[data-testid="tweet"]')) {
      const it = parseArticle(art);
      if (it && !items.has(it.url)) { items.set(it.url, it); added++; }
    }
    if (added) save();
    return added;
  }

  /** 한 화면 정도 아래로 스크롤. 실제로 내려갔으면 true */
  function scrollStep() {
    const before = window.scrollY;
    window.scrollBy(0, Math.round(window.innerHeight * 0.85));
    return window.scrollY > before + 1;
  }

  function download(filename, text, mime) {
    const blob = new Blob([text], { type: mime });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }

  const csvCell = (v) => `"${String(v).replace(/"/g, '""')}"`;
  const stamp = () => new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');

  // ---------------- 기능 1: 링크 수집 ----------------
  async function collectAll() {
    if (running) return;
    running = 'collect'; stopRequested = false; render();
    window.scrollTo(0, 0);
    await sleep(800);
    let idle = 0;
    while (!stopRequested && idle < CFG.idleRoundsToStop) {
      const added = harvest();
      const moved = scrollStep();
      // 새 항목도 없고 더 내려가지도 못했을 때만 '끝'으로 간주
      idle = added || moved ? 0 : idle + 1;
      setStatus(`수집 중… ${items.size}개${idle ? ` (끝 확인 중 ${idle}/${CFG.idleRoundsToStop})` : ''}`);
      await sleep(CFG.scrollWaitMs);
    }
    harvest();
    running = null;
    setStatus(stopRequested ? `중지됨. 총 ${items.size}개` : `수집 완료! 총 ${items.size}개`);
    render();
  }

  // ---------------- 기능 2: 좋아요 모두 해제 ----------------
  async function unlikeAll() {
    if (running) return;
    const ok = confirm(
      '정말 좋아요를 모두 해제할까요?\n\n' +
      '· 해제하기 직전에 각 게시물 링크를 자동으로 저장합니다.\n' +
      '· 되돌릴 수 없습니다. (먼저 링크를 다운로드해 두는 걸 추천)\n' +
      '· 너무 빠르면 X가 일시적으로 막을 수 있어 천천히 진행합니다.'
    );
    if (!ok) return;

    running = 'unlike'; stopRequested = false; render();
    window.scrollTo(0, 0);
    await sleep(800);

    let fails = 0, idle = 0, sinceBatch = 0;
    while (!stopRequested) {
      harvest(); // 해제 전에 화면의 링크부터 저장
      const btn = [...document.querySelectorAll('article[data-testid="tweet"] [data-testid="unlike"]')]
        .find((b) => b.isConnected);

      if (!btn) {
        idle = scrollStep() ? 0 : idle + 1;
        if (idle >= CFG.idleRoundsToStop) break;
        setStatus(`해제 ${unlikedCount}개 · 더 불러오는 중…${idle ? ` (${idle}/${CFG.idleRoundsToStop})` : ''}`);
        await sleep(CFG.scrollWaitMs);
        continue;
      }
      idle = 0;

      const article = btn.closest('article');
      const info = article && parseArticle(article);
      btn.scrollIntoView({ block: 'center' });
      btn.click();
      await sleep(rand(CFG.unlikeDelayMin, CFG.unlikeDelayMax));

      // 성공 확인: 같은 게시물의 버튼이 'like' 로 바뀌었거나 게시물이 사라졌으면 성공
      const stillLiked = article && article.isConnected && article.querySelector('[data-testid="unlike"]');
      if (stillLiked) {
        fails++;
        setStatus(`해제 실패 ${fails}/${CFG.maxConsecutiveFails} (X 제한일 수 있음)`);
        if (fails >= CFG.maxConsecutiveFails) {
          alert('연속으로 좋아요 해제에 실패했습니다.\nX의 요청 제한(rate limit)일 가능성이 큽니다.\n15분~몇 시간 뒤 다시 시도하세요.');
          break;
        }
        await sleep(5000);
        continue;
      }
      fails = 0;
      unlikedCount++;
      if (info) { info.unliked = true; items.set(info.url, { ...items.get(info.url), ...info }); save(); }
      setStatus(`해제 ${unlikedCount}개 완료 · ${info ? info.url : ''}`);

      if (++sinceBatch >= CFG.batchSize) {
        sinceBatch = 0;
        for (let s = CFG.batchPauseMs / 1000; s > 0 && !stopRequested; s--) {
          setStatus(`해제 ${unlikedCount}개 · 제한 회피를 위해 ${s}초 쉬는 중…`);
          await sleep(1000);
        }
      }
    }

    running = null;
    setStatus(stopRequested
      ? `중지됨. 이번에 ${unlikedCount}개 해제`
      : `끝! 이번에 ${unlikedCount}개 해제. 남은 게 보이면 새로고침 후 다시 실행하세요.`);
    render();
  }

  // ---------------- 내보내기 ----------------
  const list = () => [...items.values()];
  const exportTxt = () => download(`x-likes-${stamp()}.txt`, list().map((i) => i.url).join('\n'), 'text/plain');
  const exportJson = () => download(`x-likes-${stamp()}.json`, JSON.stringify(list(), null, 2), 'application/json');
  const exportCsv = () => {
    const rows = [['url', 'author', 'tweet_time', 'text', 'unliked', 'collected_at']]
      .concat(list().map((i) => [i.url, i.author, i.time, i.text, i.unliked ? 'Y' : '', i.collectedAt]));
    download(`x-likes-${stamp()}.csv`, '﻿' + rows.map((r) => r.map(csvCell).join(',')).join('\n'), 'text/csv');
  };
  async function copyLinks() {
    const text = list().map((i) => i.url).join('\n');
    try { await navigator.clipboard.writeText(text); setStatus(`${items.size}개 링크를 복사했습니다`); }
    catch (_) { prompt('복사하세요 (Ctrl+C)', text); }
  }
  function clearSaved() {
    if (!confirm(`저장된 링크 ${items.size}개를 지울까요?`)) return;
    items.clear(); save(); setStatus('저장된 링크를 지웠습니다'); render();
  }

  // ---------------- UI ----------------
  const panel = document.createElement('div');
  panel.id = 'x-likes-tool';
  panel.innerHTML = `
    <style>
      #x-likes-tool{position:fixed;right:16px;bottom:16px;z-index:99999;width:260px;
        font:13px/1.4 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:#0f1419;
        background:#fff;border:1px solid #cfd9de;border-radius:12px;box-shadow:0 4px 20px rgba(0,0,0,.15);padding:12px}
      #x-likes-tool h3{margin:0 0 6px;font-size:14px;display:flex;justify-content:space-between;align-items:center}
      #x-likes-tool .min{cursor:pointer;border:0;background:none;font-size:16px;color:#536471;padding:0 4px}
      #x-likes-tool .count{font-size:22px;font-weight:700;margin:2px 0}
      #x-likes-tool .status{color:#536471;font-size:12px;min-height:32px;word-break:break-all;margin-bottom:8px}
      #x-likes-tool .row{display:flex;gap:6px;margin-top:6px}
      #x-likes-tool button.b{flex:1;cursor:pointer;border:1px solid #cfd9de;background:#fff;color:#0f1419;
        border-radius:999px;padding:6px 8px;font:inherit;font-weight:600}
      #x-likes-tool button.b:hover:not(:disabled){background:#f2f4f5}
      #x-likes-tool button.b:disabled{opacity:.45;cursor:default}
      #x-likes-tool button.primary{background:#0f1419;color:#fff;border-color:#0f1419}
      #x-likes-tool button.primary:hover:not(:disabled){background:#272c30}
      #x-likes-tool button.danger{background:#f4212e;color:#fff;border-color:#f4212e}
      #x-likes-tool button.danger:hover:not(:disabled){background:#dc1e29}
      #x-likes-tool .warn{color:#b8860b;font-size:12px;margin-bottom:6px}
      #x-likes-tool.collapsed .body{display:none}
      @media (prefers-color-scheme: dark){
        #x-likes-tool{background:#15202b;color:#e7e9ea;border-color:#38444d}
        #x-likes-tool button.b{background:#15202b;color:#e7e9ea;border-color:#536471}
        #x-likes-tool button.b:hover:not(:disabled){background:#1e2732}
        #x-likes-tool button.primary{background:#e7e9ea;color:#0f1419;border-color:#e7e9ea}
        #x-likes-tool .status,#x-likes-tool .min{color:#8b98a5}
      }
    </style>
    <h3><span>❤️ 좋아요 도구</span><button class="min" data-act="toggle" title="접기/펼치기">–</button></h3>
    <div class="body">
      <div class="warn" data-el="warn"></div>
      <div class="count" data-el="count">0개</div>
      <div class="status" data-el="status">likes 페이지에서 "링크 수집"을 누르세요.</div>
      <div class="row"><button class="b primary" data-act="collect">링크 수집</button>
                       <button class="b" data-act="stop">중지</button></div>
      <div class="row"><button class="b" data-act="txt">TXT</button>
                       <button class="b" data-act="csv">CSV</button>
                       <button class="b" data-act="json">JSON</button></div>
      <div class="row"><button class="b" data-act="copy">링크 복사</button>
                       <button class="b" data-act="clear">기록 삭제</button></div>
      <div class="row"><button class="b danger" data-act="unlike">좋아요 모두 해제</button></div>
    </div>`;

  const $ = (sel) => panel.querySelector(sel);
  function setStatus(msg) { $('[data-el="status"]').textContent = msg; $('[data-el="count"]').textContent = `${items.size}개`; }

  function render() {
    const onLikes = isLikesPage();
    $('[data-el="warn"]').textContent = onLikes ? '' : '⚠️ x.com/내아이디/likes 페이지로 이동하세요.';
    $('[data-el="count"]').textContent = `${items.size}개`;
    const busy = !!running;
    $('[data-act="collect"]').disabled = busy || !onLikes;
    $('[data-act="unlike"]').disabled = busy || !onLikes;
    $('[data-act="stop"]').disabled = !busy;
    for (const k of ['txt', 'csv', 'json', 'copy', 'clear']) $(`[data-act="${k}"]`).disabled = !items.size || (k === 'clear' && busy);
  }

  panel.addEventListener('click', (e) => {
    const act = e.target.closest('[data-act]')?.dataset.act;
    if (!act) return;
    ({
      toggle: () => panel.classList.toggle('collapsed'),
      collect: collectAll,
      unlike: unlikeAll,
      stop: () => { stopRequested = true; setStatus('중지하는 중…'); },
      txt: exportTxt, csv: exportCsv, json: exportJson,
      copy: copyLinks, clear: clearSaved,
    })[act]?.();
  });

  document.body.appendChild(panel);
  render();
  // X 는 SPA 라 페이지 이동 시 새로고침이 없음 → 주기적으로 상태 갱신
  setInterval(() => { if (!running) render(); }, 1000);
})();

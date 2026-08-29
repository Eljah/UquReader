package com.example.uqureader.webapp;

final class StaticReaderAssets {
    private StaticReaderAssets() {
    }

    static final String INDEX_HTML = """
            <!doctype html>
            <html lang="ru">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>UquReader Web</title>
              <link rel="stylesheet" href="/reader/style.css">
            </head>
            <body>
              <main class="shell">
                <section class="auth" id="authPanel">
                  <div>
                    <h1>UquReader</h1>
                    <p class="motto">UquReader: укыгыч, укыткыч вә укыттыргыч</p>
                    <p>Веб-читалка татарских книг с морфологией, серверной озвучкой Talgat и долгосрочной статистикой чтения.</p>
                  </div>
                  <form id="authForm">
                    <input id="username" autocomplete="username" placeholder="Логин" required>
                    <input id="password" autocomplete="current-password" placeholder="Пароль" type="password" required>
                    <div class="auth-actions">
                      <button type="submit" data-mode="login">Войти</button>
                      <button type="button" id="registerButton">Создать</button>
                    </div>
                    <p id="authMessage" class="message"></p>
                  </form>
                </section>

                <section class="reader hidden" id="readerPanel">
                  <header class="toolbar">
                    <strong class="app-title">UquReader</strong>
                    <select id="workSelect" aria-label="Книга"></select>
                    <button id="prevPage" title="Предыдущая страница">←</button>
                    <span id="pageStatus"></span>
                    <button id="nextPage" title="Следующая страница">→</button>
                    <button id="speakPage" title="Озвучить или поставить на паузу">▶</button>
                    <button id="statsButton">Статистика</button>
                    <button id="logoutButton">Выйти</button>
                    <span id="speechStatus" class="speech-status"></span>
                  </header>
                  <article id="page" class="page" aria-live="polite"></article>
                  <section id="statsPanel" class="stats-panel hidden">
                    <div class="stats-head">
                      <h2>Статистика чтения</h2>
                      <button id="closeStats">Закрыть</button>
                    </div>
                    <div class="stats-tabs">
                      <button id="lemmaStatsTab" class="active">Леммы</button>
                      <button id="featureStatsTab">Признаки</button>
                    </div>
                    <div id="statsContent" class="stats-content"></div>
                  </section>
                  <aside id="tokenSheet" class="token-sheet hidden">
                    <button id="closeSheet" title="Закрыть">×</button>
                    <h2 id="tokenSurface"></h2>
                    <dl>
                      <dt>Лемма</dt><dd id="tokenLemma"></dd>
                      <dt>Разбор</dt><dd id="tokenBreakdown"></dd>
                      <dt>Часть речи</dt><dd id="tokenPos"></dd>
                      <dt>Признаки</dt><dd id="tokenFeatures"></dd>
                      <dt>Перевод</dt><dd id="tokenTranslations"></dd>
                    </dl>
                    <section id="tokenAnalysesSection" class="analysis-variants hidden">
                      <h3>Варианты Vienna</h3>
                      <div id="tokenAnalyses"></div>
                    </section>
                    <button id="speakToken">Озвучить</button>
                  </aside>
                </section>
              </main>
              <script src="/reader/app.js"></script>
            </body>
            </html>
            """;

    static final String STYLE_CSS = """
            :root {
              color-scheme: light;
              --bg: #F5EEDB;
              --surface: #E2C171;
              --surface-muted: #F1E4C4;
              --primary: #24324D;
              --primary-dark: #1B253A;
              --accent-red: #C24432;
              --accent-blue: #2B4F7F;
              --ink: #2B2623;
              --muted: #5B5047;
              --toolbar-icon: #F7F0DF;
              --control-highlight: rgba(194, 68, 50, .20);
              --seen: rgba(43, 38, 35, .10);
              --lookup: rgba(194, 68, 50, .18);
              --speech: rgba(36, 50, 77, .12);
              --speech-token: rgba(194, 68, 50, .28);
              --shadow: rgba(36, 50, 77, .24);
              --loading-track: rgba(36, 50, 77, .18);
            }
            * { box-sizing: border-box; }
            body {
              margin: 0;
              font-family: "Arial Narrow", "Roboto Condensed", "Segoe UI", system-ui, -apple-system, sans-serif;
              background: var(--bg);
              color: var(--ink);
            }
            .shell { min-height: 100vh; }
            .hidden { display: none !important; }
            .auth {
              min-height: 100vh;
              display: grid;
              grid-template-columns: minmax(280px, 420px) minmax(280px, 360px);
              align-items: center;
              justify-content: center;
              gap: 48px;
              padding: 32px;
            }
            .auth h1 { margin: 0 0 12px; font-size: 42px; letter-spacing: .05em; color: var(--primary); }
            .auth .motto { margin: 0 0 10px; color: var(--accent-red); font-size: 21px; font-weight: 700; line-height: 1.35; }
            .auth p { margin: 0; color: var(--muted); line-height: 1.5; }
            form {
              display: grid;
              gap: 12px;
              padding: 24px;
              background: var(--surface-muted);
              border: 2px solid var(--primary);
              border-radius: 12px;
              box-shadow: 0 4px 12px rgba(36, 50, 77, .20);
            }
            input, select, button {
              font: inherit;
              min-height: 40px;
              border-radius: 6px;
              border: 2px solid var(--primary);
              background: var(--bg);
              color: var(--ink);
              padding: 0 12px;
            }
            button {
              cursor: pointer;
              background: var(--primary);
              border-color: var(--primary);
              color: var(--toolbar-icon);
              font-weight: 650;
            }
            button:disabled { opacity: .45; cursor: default; }
            .auth-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
            #registerButton, #logoutButton, #prevPage, #nextPage, #speakPage, #closeSheet {
              background: transparent;
              color: var(--toolbar-icon);
            }
            #speakPage.playing {
              background: var(--accent-red);
              color: var(--toolbar-icon);
            }
            .message { min-height: 20px; font-size: 14px; color: var(--accent-red); }
            .reader { min-height: 100vh; display: grid; grid-template-rows: auto 1fr; }
            .toolbar {
              position: sticky;
              top: 0;
              z-index: 3;
              display: grid;
              grid-template-columns: auto minmax(180px, 1fr) 40px auto 40px 40px auto auto minmax(160px, auto);
              gap: 8px;
              align-items: center;
              min-height: 56px;
              padding: 6px 8px;
              background: var(--primary);
              box-shadow: 0 4px 10px rgba(27, 37, 58, .30);
            }
            .toolbar select {
              min-height: 40px;
              background: var(--surface-muted);
              border-color: var(--primary-dark);
              color: var(--ink);
            }
            .app-title {
              color: var(--toolbar-icon);
              font-size: 20px;
              font-weight: 700;
              letter-spacing: .05em;
              line-height: 1;
              padding: 0 8px;
              white-space: nowrap;
            }
            .toolbar button {
              min-width: 40px;
              min-height: 40px;
              padding: 0 8px;
              border-color: transparent;
              background: transparent;
              color: var(--toolbar-icon);
            }
            .toolbar button:hover { background: var(--control-highlight); }
            #pageStatus {
              min-width: 7em;
              color: var(--accent-red);
              font-weight: 700;
              letter-spacing: .05em;
              text-align: center;
              white-space: nowrap;
            }
            .speech-status { color: var(--toolbar-icon); font-size: 14px; min-width: 0; overflow-wrap: anywhere; }
            .page {
              position: relative;
              width: calc(100vw - 48px);
              max-width: 980px;
              min-height: calc(100vh - 56px - 56px);
              margin: 24px auto 88px;
              padding: 24px;
              background: var(--surface);
              border: 3px solid var(--primary);
              border-radius: 16px;
              box-shadow: 0 4px 12px var(--shadow);
              font-family: Georgia, "Times New Roman", "Noto Serif", serif;
              font-size: 22px;
              line-height: 1.55;
              letter-spacing: .01em;
              overflow-wrap: break-word;
              hyphens: none;
              text-rendering: optimizeLegibility;
              font-kerning: normal;
              text-align: justify;
            }
            .reader-paragraph {
              margin: 0;
              text-indent: 0;
            }
            .reader-paragraph:empty { min-height: 1em; }
            .reader-line-break { display: block; height: 0; }
            .page .token:first-child,
            .reader-paragraph > .token:first-child { margin-left: 0; }
            .reader-paragraph .token[data-punctuation="true"] { margin-left: 0; }
            .reader-paragraph .token.dialogue-dash { margin-left: -.15em; }
            .reader-paragraph .token.dialogue-dash + .token { margin-left: .08em; }
            .page::selection,
            .token::selection {
              background: var(--control-highlight);
            }
            .token {
              border-radius: 6px;
              padding: 1px 2px;
              transition: background .12s ease, box-shadow .12s ease;
            }
            .token:hover { background: var(--lookup); }
            .token.visible { background: var(--seen); }
            .token.speech-sentence { background: var(--speech); }
            .token.speech-focus {
              background: var(--speech-token);
              color: var(--accent-red);
              box-shadow: inset 0 0 0 2px var(--accent-red);
            }
            .page.loading {
              opacity: .68;
            }
            .page-loading {
              display: grid;
              justify-items: center;
              align-content: center;
              min-height: 320px;
              gap: 16px;
              color: var(--primary);
              text-align: center;
            }
            .page-spinner {
              width: 44px;
              height: 44px;
              border-radius: 50%;
              border: 4px solid var(--loading-track);
              border-top-color: var(--accent-red);
              animation: page-spin .8s linear infinite;
            }
            .page-loading strong {
              font-family: "Arial Narrow", "Roboto Condensed", "Segoe UI", system-ui, sans-serif;
              font-size: 18px;
              letter-spacing: 0;
            }
            .page-loading span {
              font-family: "Arial Narrow", "Roboto Condensed", "Segoe UI", system-ui, sans-serif;
              font-size: 14px;
              color: var(--muted);
            }
            @keyframes page-spin {
              to { transform: rotate(360deg); }
            }
            .token-sheet {
              position: fixed;
              right: 16px;
              bottom: 16px;
              width: min(420px, calc(100vw - 32px));
              max-height: min(560px, calc(100vh - 32px));
              overflow: auto;
              padding: 18px;
              background: var(--surface-muted);
              border: 2px solid var(--primary);
              border-radius: 12px;
              box-shadow: 0 4px 14px var(--shadow);
              z-index: 4;
            }
            .token-sheet h2 { margin: 0 36px 12px 0; font-size: 26px; }
            .token-sheet dl { display: grid; grid-template-columns: 96px 1fr; gap: 8px 12px; margin: 0 0 16px; }
            .token-sheet dt { color: var(--muted); }
            .token-sheet dd { margin: 0; }
            .analysis-variants {
              margin: 0 0 16px;
              padding-top: 12px;
              border-top: 1px solid rgba(36, 50, 77, .26);
            }
            .analysis-variants h3 {
              margin: 0 0 8px;
              font-size: 16px;
              color: var(--primary);
            }
            .analysis-variant {
              display: grid;
              gap: 4px;
              padding: 8px 0;
              border-bottom: 1px solid rgba(36, 50, 77, .16);
              font-size: 14px;
            }
            .analysis-variant:last-child { border-bottom: 0; }
            .analysis-variant strong { color: var(--primary); }
            .analysis-variant span { color: var(--muted); overflow-wrap: anywhere; }
            .analysis-feature-rows {
              display: grid;
              gap: 6px;
              margin-top: 4px;
            }
            .analysis-feature-row {
              display: grid;
              grid-template-columns: minmax(64px, 112px) 1fr;
              gap: 8px;
              align-items: start;
              padding: 6px 8px;
              border: 1px solid rgba(36, 50, 77, .12);
              background: rgba(255, 255, 255, .5);
            }
            .analysis-feature-segment {
              color: var(--primary);
              font-weight: 700;
              overflow-wrap: anywhere;
            }
            .analysis-feature-description {
              color: var(--muted);
              overflow-wrap: anywhere;
            }
            #closeSheet { position: absolute; right: 10px; top: 10px; min-width: 34px; width: 34px; height: 34px; padding: 0; }
            .stats-panel {
              position: fixed;
              inset: 64px 16px 16px;
              z-index: 5;
              display: grid;
              grid-template-rows: auto auto 1fr;
              width: min(1040px, calc(100vw - 32px));
              margin-left: auto;
              padding: 18px;
              background: var(--bg);
              border: 2px solid var(--primary);
              border-radius: 12px;
              box-shadow: 0 4px 14px var(--shadow);
            }
            .stats-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
            .stats-head h2 { margin: 0; font-size: 24px; color: var(--primary); letter-spacing: .05em; }
            .stats-tabs { display: flex; gap: 8px; padding: 14px 0; }
            .stats-tabs button { background: var(--surface-muted); color: var(--primary); border-color: var(--primary); }
            .stats-tabs button.active { background: var(--primary); color: var(--toolbar-icon); }
            .stats-content { overflow: auto; border-top: 2px solid var(--primary); }
            .stat-row {
              display: grid;
              grid-template-columns: minmax(120px, 1fr) 90px 90px 90px 130px;
              gap: 10px;
              align-items: center;
              min-height: 44px;
              border-bottom: 1px solid rgba(36, 50, 77, .22);
              font-size: 15px;
            }
            .stat-row.header { position: sticky; top: 0; background: var(--surface-muted); color: var(--muted); font-weight: 700; z-index: 1; }
            .stat-row.clickable { cursor: pointer; }
            .stat-row.clickable:hover { background: var(--surface-muted); }
            .stat-main { min-width: 0; overflow-wrap: anywhere; }
            .stat-sub { color: var(--muted); font-size: 13px; }
            .timeline-panel {
              padding: 14px 0 18px;
              border-bottom: 2px solid var(--primary);
            }
            .timeline-title {
              display: flex;
              justify-content: space-between;
              gap: 12px;
              align-items: center;
              margin-bottom: 8px;
            }
            .timeline-title strong { font-size: 17px; }
            .timeline-title button { background: var(--surface-muted); color: var(--primary); border-color: var(--primary); }
            .timeline-axis {
              display: flex;
              justify-content: space-between;
              color: var(--muted);
              font-size: 12px;
              margin-top: 4px;
            }
            .timeline-svg {
              width: 100%;
              height: 72px;
              display: block;
              background: var(--surface-muted);
              border: 2px solid var(--primary);
              border-radius: 12px;
            }
            .timeline-legend { display: flex; gap: 14px; flex-wrap: wrap; color: var(--muted); font-size: 13px; }
            .legend-dot { display: inline-block; width: 9px; height: 9px; border-radius: 99px; margin-right: 5px; vertical-align: baseline; }
            @media (max-width: 760px) {
              .auth { grid-template-columns: 1fr; align-content: center; gap: 24px; }
              .toolbar { grid-template-columns: auto 1fr 40px auto 40px 40px; }
              .app-title { font-size: 16px; padding-right: 0; }
              #statsButton, #logoutButton, #speechStatus { grid-column: 1 / -1; }
              .page { width: calc(100vw - 48px); padding: 24px; font-size: 20px; line-height: 1.55; }
              .stat-row { grid-template-columns: 1fr 70px 70px; }
              .stat-row .wide-only { display: none; }
            }
            """;

    static final String APP_JS = """
            const state = {
              user: null,
              works: [],
              workId: null,
              pageIndex: 0,
              pageSize: 450,
              pageRequestId: 0,
              isLoadingPage: false,
              hasNext: false,
              tokens: [],
              visibleSince: new Map(),
              visibleMs: new Map(),
              queue: [],
              selectedToken: null,
              flushTimer: null,
              observer: null,
              statsMode: 'lemmas',
              lastLemmaRows: [],
              grammar: {pos: {}, features: {}},
              speech: {
                mode: 'idle',
                sentenceIndex: 0,
                ranges: [],
                cache: new Map(),
                audio: null,
                progressTimer: null
              }
            };

            const $ = id => document.getElementById(id);
            const EVENT_COLORS = {
              token_committed: '#176d3b',
              token_exposed: '#6ea56f',
              token_lookup: '#1d66b2',
              token_tts_played: '#9b3d2e',
              page_visible: '#7a8790'
            };

            async function api(path, options = {}) {
              const response = await fetch(path, {
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json', ...(options.headers || {})},
                ...options
              });
              const text = await response.text();
              const data = text ? JSON.parse(text) : {};
              if (!response.ok) throw new Error(data.message || response.statusText);
              return data;
            }

            function eventId() {
              if (crypto.randomUUID) return crypto.randomUUID();
              return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
            }

            function tokenPayload(token, eventType, visibleMs = 0) {
              const morph = token.morphology || {};
              return {
                clientEventId: eventId(),
                eventType,
                workId: state.workId,
                pageIndex: state.pageIndex,
                tokenIndex: token.index,
                lemma: morph.lemma || '',
                pos: morph.pos || '',
                featureKey: morph.featureKey || '',
                charIndex: token.charStart,
                visibleMs,
                occurredAtMs: Date.now()
              };
            }

            function tokenPayloads(token, eventType, visibleMs = 0) {
              const variants = Array.isArray(token.analyses) ? token.analyses : [];
              const payloads = [];
              const seen = new Set();
              for (const variant of variants) {
                const morph = variant.morphology || {};
                const lemma = variant.lemma || morph.lemma || '';
                const pos = morph.pos || firstNonEmpty(variant.pos || []);
                if (!lemma || !pos) continue;
                const featureKey = morph.featureKey || [pos, ...(morph.features || []).map(f => f.code).filter(Boolean)].filter(Boolean).join('+');
                const key = `${lemma}\u0000${pos}\u0000${featureKey}\u0000${eventType}`;
                if (seen.has(key)) continue;
                seen.add(key);
                payloads.push({
                  clientEventId: eventId(),
                  eventType,
                  workId: state.workId,
                  pageIndex: state.pageIndex,
                  tokenIndex: token.index,
                  lemma,
                  pos,
                  featureKey,
                  charIndex: token.charStart,
                  visibleMs,
                  occurredAtMs: Date.now()
                });
              }
              return payloads.length ? payloads : [tokenPayload(token, eventType, visibleMs)];
            }

            function firstNonEmpty(values) {
              for (const value of values || []) {
                if (value) return value;
              }
              return '';
            }

            function enqueue(events) {
              if (!Array.isArray(events)) events = [events];
              state.queue.push(...events);
              localStorage.setItem('uqureader.pendingEvents', JSON.stringify(state.queue.slice(-5000)));
              scheduleFlush(1200);
            }

            function restoreQueue() {
              try {
                const saved = JSON.parse(localStorage.getItem('uqureader.pendingEvents') || '[]');
                if (Array.isArray(saved)) state.queue.push(...saved);
              } catch (_) {}
            }

            function scheduleFlush(delay = 5000) {
              if (state.flushTimer) return;
              state.flushTimer = setTimeout(() => {
                state.flushTimer = null;
                flushEvents();
              }, delay);
            }

            async function flushEvents(useBeacon = false) {
              commitVisible(false);
              if (!state.queue.length) return;
              const batch = state.queue.splice(0, 250);
              localStorage.setItem('uqureader.pendingEvents', JSON.stringify(state.queue));
              const payload = JSON.stringify({events: batch});
              if (useBeacon && navigator.sendBeacon) {
                const blob = new Blob([payload], {type: 'application/json'});
                if (navigator.sendBeacon('/api/reading/events', blob)) return;
              }
              try {
                await fetch('/api/reading/events', {
                  method: 'POST',
                  credentials: 'same-origin',
                  keepalive: useBeacon,
                  headers: {'Content-Type': 'application/json'},
                  body: payload
                }).then(r => {
                  if (!r.ok) throw new Error('flush failed');
                  return r.json();
                });
              } catch (_) {
                state.queue.unshift(...batch);
                localStorage.setItem('uqureader.pendingEvents', JSON.stringify(state.queue.slice(-5000)));
              }
              if (state.queue.length) scheduleFlush(2000);
            }

            function commitVisible(finalCommit) {
              const now = performance.now();
              const events = [];
              for (const [index, started] of state.visibleSince.entries()) {
                const elapsed = Math.round(now - started);
                state.visibleSince.set(index, now);
                state.visibleMs.set(index, (state.visibleMs.get(index) || 0) + elapsed);
              }
              for (const token of state.tokens) {
                const total = state.visibleMs.get(token.index) || 0;
                if (total >= 700) {
                  events.push(...tokenPayloads(token, finalCommit ? 'token_committed' : 'token_exposed', total));
                  state.visibleMs.set(token.index, 0);
                }
              }
              if (events.length) {
                state.queue.push(...events);
                localStorage.setItem('uqureader.pendingEvents', JSON.stringify(state.queue.slice(-5000)));
              }
            }

            async function authenticate(mode) {
              const username = $('username').value.trim();
              const password = $('password').value;
              try {
                state.user = await api(`/api/auth/${mode}`, {method: 'POST', body: JSON.stringify({username, password})});
                $('authPanel').classList.add('hidden');
                $('readerPanel').classList.remove('hidden');
                await loadWorks();
              } catch (error) {
                $('authMessage').textContent = error.message;
              }
            }

            async function loadMe() {
              const me = await api('/api/auth/me');
              if (me.authenticated) {
                state.user = me;
                $('authPanel').classList.add('hidden');
                $('readerPanel').classList.remove('hidden');
                await loadWorks();
              }
            }

            async function loadWorks() {
              await loadGrammar();
              const data = await api('/api/works');
              state.works = data.works || [];
              $('workSelect').innerHTML = state.works.map(w => `<option value="${w.id}">${escapeHtml(w.title)} · ${w.tokenCount}</option>`).join('');
              if (state.works.length) {
                state.workId = state.workId || state.works[0].id;
                $('workSelect').value = state.workId;
                await loadPage(0);
              }
            }

            async function loadGrammar() {
              if (Object.keys(state.grammar.features).length) return;
              const data = await api('/api/grammar');
              state.grammar.pos = Object.fromEntries((data.pos || []).map(item => [item.code, item]));
              state.grammar.features = Object.fromEntries((data.features || []).map(item => [item.code, item]));
            }

            async function loadPage(pageIndex) {
              const requestId = ++state.pageRequestId;
              const targetPage = Math.max(0, pageIndex);
              const targetWork = state.workId;
              setPageLoading(true, targetPage);
              stopSpeech(false);
              commitVisible(true);
              await new Promise(resolve => requestAnimationFrame(resolve));
              await flushEvents();
              try {
                const data = await api(`/api/works/${encodeURIComponent(targetWork)}/tokens?page=${targetPage}&pageSize=${state.pageSize}`);
                if (requestId !== state.pageRequestId || targetWork !== state.workId) return;
                state.pageIndex = targetPage;
                state.tokens = data.tokens || [];
                state.hasNext = Boolean(data.hasNext);
                renderPage();
                await api('/api/reading/state', {
                  method: 'POST',
                  body: JSON.stringify({workId: state.workId, pageIndex: state.pageIndex, charIndex: state.tokens[0]?.charStart || 0})
                });
                enqueue({
                  clientEventId: eventId(),
                  eventType: 'page_visible',
                  workId: state.workId,
                  pageIndex: state.pageIndex,
                  tokenIndex: -1,
                  lemma: '',
                  pos: '',
                  featureKey: '',
                  charIndex: state.tokens[0]?.charStart || 0,
                  visibleMs: 0,
                  occurredAtMs: Date.now()
                });
              } catch (error) {
                if (requestId === state.pageRequestId) {
                  renderPageError(error);
                }
              } finally {
                if (requestId === state.pageRequestId) {
                  setPageLoading(false);
                }
              }
            }

            function setPageLoading(loading, pageIndex = state.pageIndex) {
              state.isLoadingPage = loading;
              const page = $('page');
              page.classList.toggle('loading', loading);
              if (loading) {
                if (state.observer) state.observer.disconnect();
                page.innerHTML = `
                  <div class="page-loading">
                    <div class="page-spinner" aria-hidden="true"></div>
                    <strong>Загрузка книги</strong>
                    <span>Страница ${pageIndex + 1}</span>
                  </div>`;
              }
              updateReaderControls();
            }

            function updateReaderControls() {
              $('workSelect').disabled = state.isLoadingPage;
              $('prevPage').disabled = state.isLoadingPage || state.pageIndex === 0;
              $('nextPage').disabled = state.isLoadingPage || !state.hasNext;
              $('speakPage').disabled = state.isLoadingPage || state.tokens.length === 0;
              $('pageStatus').textContent = state.isLoadingPage ? 'Загрузка...' : `${state.pageIndex + 1}`;
            }

            function renderPageError(error) {
              $('page').innerHTML = `
                <div class="page-loading">
                  <strong>Не удалось загрузить книгу</strong>
                  <span>${escapeHtml(error.message || 'Ошибка загрузки')}</span>
                </div>`;
              state.tokens = [];
              state.hasNext = false;
            }

            function renderPage() {
              if (state.observer) state.observer.disconnect();
              state.visibleSince.clear();
              state.visibleMs.clear();
              const page = $('page');
              page.textContent = '';
              const fragment = document.createDocumentFragment();
              let paragraph = createParagraph();
              fragment.append(paragraph);
              for (const token of state.tokens) {
                paragraph = appendPrefix(paragraph, token.prefix || '', fragment);
                const surface = token.surface || '';
                if (/^[\\r\\n]+$/.test(surface)) {
                  paragraph = appendPrefix(paragraph, surface, fragment);
                  continue;
                }
                const span = document.createElement('span');
                span.className = tokenClasses(token);
                span.textContent = typographicSurface(surface);
                span.dataset.index = token.index;
                if (isPunctuation(surface)) span.dataset.punctuation = 'true';
                span.tabIndex = 0;
                span.addEventListener('click', () => openToken(token));
                paragraph.append(span);
              }
              page.append(fragment);
              page.classList.remove('loading');
              updateReaderControls();
              state.speech.ranges = buildSentenceRanges();
              state.observer = new IntersectionObserver(entries => {
                const now = performance.now();
                for (const entry of entries) {
                  const index = Number(entry.target.dataset.index);
                  if (entry.isIntersecting && entry.intersectionRatio > 0.65) {
                    state.visibleSince.set(index, now);
                    entry.target.classList.add('visible');
                  } else {
                    const started = state.visibleSince.get(index);
                    if (started) {
                      state.visibleMs.set(index, (state.visibleMs.get(index) || 0) + Math.round(now - started));
                      state.visibleSince.delete(index);
                    }
                    entry.target.classList.remove('visible');
                  }
                }
              }, {threshold: [0, .65, 1]});
              page.querySelectorAll('.token').forEach(node => state.observer.observe(node));
            }

            function createParagraph() {
              const paragraph = document.createElement('p');
              paragraph.className = 'reader-paragraph';
              return paragraph;
            }

            function appendPrefix(paragraph, prefix, fragment) {
              if (!prefix) return paragraph;
              const normalized = prefix.replace(/\\r\\n/g, '\\n').replace(/\\r/g, '\\n');
              const pieces = normalized.split(/(\\n+)/);
              for (const piece of pieces) {
                if (!piece) continue;
                if (/\\n+/.test(piece)) {
                  if (piece.length >= 2) {
                    paragraph = createParagraph();
                    fragment.append(paragraph);
                  } else if (paragraph.childNodes.length > 0) {
                    paragraph.append(document.createElement('br'));
                    const spacer = document.createElement('span');
                    spacer.className = 'reader-line-break';
                    paragraph.append(spacer);
                  }
                } else {
                  paragraph.append(document.createTextNode(typographicPrefix(piece)));
                }
              }
              return paragraph;
            }

            function tokenClasses(token) {
              const surface = token.surface || '';
              const classes = ['token'];
              if (/^[—–-]$/.test(surface)) classes.push('dialogue-dash');
              return classes.join(' ');
            }

            function isPunctuation(surface) {
              return /^[.,:;!?…\\)\\]\\}]+$/u.test(surface);
            }

            function typographicPrefix(value) {
              return value
                .replace(/\\u00a0/g, ' ')
                .replace(/[ \\t]+/g, ' ');
            }

            function typographicSurface(value) {
              return value
                .replace(/\\.\\.\\./g, '…')
                .replace(/--/g, '—');
            }

            async function openToken(token) {
              state.selectedToken = token;
              renderTokenSheet(token);
              $('tokenSheet').classList.remove('hidden');
              enqueue(tokenPayloads(token, 'token_lookup'));
              if (token.fullAnalysesLoaded || token.loadingAnalyses) return;
              token.loadingAnalyses = true;
              try {
                const data = await api(`/api/works/${encodeURIComponent(state.workId)}/tokens/${token.index}`);
                if (state.selectedToken !== token || !data.token) return;
                Object.assign(token, data.token, {fullAnalysesLoaded: true, loadingAnalyses: false});
                renderTokenSheet(token);
              } catch (_) {
                token.loadingAnalyses = false;
              }
            }

            function renderTokenSheet(token) {
              const morph = token.morphology || {};
              $('tokenSurface').textContent = token.surface;
              $('tokenLemma').textContent = morph.lemma || '—';
              $('tokenBreakdown').textContent = formatBreakdown(token.surface, morph.segments || []);
              $('tokenPos').textContent = formatPos(morph.pos);
              $('tokenFeatures').textContent = formatMorphFeatures(morph.features || []);
              $('tokenTranslations').textContent = (token.translations || []).join(', ') || '—';
              renderAnalysisVariants(token.analyses || []);
            }

            function renderAnalysisVariants(analyses) {
              const section = $('tokenAnalysesSection');
              const container = $('tokenAnalyses');
              if (!analyses || analyses.length === 0) {
                container.innerHTML = '';
                section.classList.add('hidden');
                return;
              }
              section.classList.remove('hidden');
              container.innerHTML = analyses.map((variant, index) => {
                const segments = formatViennaSegments(variant.segments || []);
                const gloss = (variant.gloss || []).filter(Boolean).join(' ');
                const pos = (variant.pos || []).filter(Boolean).join(' ');
                const descriptions = [
                  ...(variant.posDescriptions || []),
                  ...(variant.glossDescriptions || [])
                ].filter(Boolean).join('; ');
                const translations = (variant.translations || []).filter(Boolean).join(', ');
                const morphology = variant.morphology || {};
                const features = morphology.features ? formatMorphFeatures(morphology.features) : '';
                return `
                  <div class="analysis-variant">
                    <strong>${index + 1}. ${escapeHtml(variant.lemma || morphology.lemma || '—')}</strong>
                    <span>${escapeHtml(segments || variant.analysis || '—')}</span>
                    <span>${escapeHtml([pos, gloss].filter(Boolean).join(' · ') || '—')}</span>
                    <span><b>Признаки:</b></span>
                    ${renderAnalysisFeatureRows(variant.featureRows || [], descriptions || (features && features !== '—' ? features : ''))}
                    <span><b>Перевод леммы:</b> ${escapeHtml(translations || '—')}</span>
                  </div>
                `;
              }).join('');
            }

            function renderAnalysisFeatureRows(rows, fallback) {
              const visible = (rows || []).filter(row => {
                const descriptions = (row.descriptions || []).filter(Boolean);
                return row.segment || row.pos || row.gloss || descriptions.length > 0;
              });
              if (visible.length === 0) {
                return `<span>${escapeHtml(fallback || '—')}</span>`;
              }
              return `
                <div class="analysis-feature-rows">
                  ${visible.map(row => {
                    const descriptions = (row.descriptions || []).filter(Boolean).join('; ');
                    const codes = [row.pos, isGrammarGloss(row.gloss) ? row.gloss : ''].filter(Boolean).join(' · ');
                    const text = descriptions || codes;
                    return `
                      <div class="analysis-feature-row">
                        <span class="analysis-feature-segment">${escapeHtml(formatViennaSegmentLabel(row.segment))}</span>
                        <span class="analysis-feature-description">${escapeHtml(text || '—')}</span>
                      </div>
                    `;
                  }).join('')}
                </div>
              `;
            }

            function formatViennaSegments(segments) {
              return (segments || [])
                .filter(Boolean)
                .map((segment, index) => index === 0 ? segment : segment.replace(/^-+/, ''))
                .join('-');
            }

            function formatViennaSegmentLabel(segment) {
              if (!segment) return '—';
              return segment.startsWith('-') ? `-${segment.replace(/^-+/, '')}` : segment;
            }

            function isGrammarGloss(gloss) {
              return /^-?[A-Z0-9_.]+$/.test(gloss || '');
            }

            function formatPos(code) {
              if (!code) return '—';
              const meta = state.grammar.pos[code];
              if (!meta) return code;
              return `${code} — ${meta.titleRu || meta.titleTt || code}`;
            }

            function formatBreakdown(surface, segments) {
              const parts = (segments || []).filter(Boolean);
              if (parts.length <= 1) return surface || '—';
              return `${surface}: ${parts.join('-')}`;
            }

            function formatFeature(code) {
              if (!code) return '';
              const meta = state.grammar.features[code];
              const title = meta?.titleRu || meta?.titleTt || code;
              const example = firstExample(meta);
              return example ? `${code} - ${title}, например "${example}"` : `${code} - ${title}`;
            }

            function formatMorphFeatures(features) {
              const labels = features.map(feature => formatFeature(feature.code)).filter(Boolean);
              return labels.length ? labels.join('; ') : '—';
            }

            function formatFeatureKey(featureKey) {
              if (!featureKey) return '—';
              const parts = String(featureKey).split('+').filter(Boolean);
              if (!parts.length) return featureKey;
              const [posCode, ...featureCodes] = parts;
              const pos = state.grammar.pos[posCode];
              const labels = [pos ? `${posCode} — ${pos.titleRu || pos.titleTt || posCode}` : posCode];
              for (const code of featureCodes) {
                labels.push(formatFeature(code));
              }
              return labels.join('; ');
            }

            function firstExample(meta) {
              const examples = meta?.examples || [];
              return examples.length ? examples[0] : '';
            }

            function buildSentenceRanges() {
              const ranges = [];
              let current = null;
              let pendingEnd = false;
              for (let i = 0; i < state.tokens.length; i++) {
                const token = state.tokens[i];
                if (pendingEnd && !isClosingPunctuation(token.surface)) {
                  pushSentence(ranges, current);
                  current = null;
                  pendingEnd = false;
                }
                if (!current) current = {startToken: i, endToken: i, text: ''};
                const part = `${token.prefix || ''}${token.surface || ''}`;
                current.text += part;
                current.endToken = i;
                if (pendingEnd && isClosingPunctuation(token.surface)) {
                  pushSentence(ranges, current);
                  current = null;
                  pendingEnd = false;
                } else if (isSentenceEnding(token.surface)) {
                  pendingEnd = true;
                } else if (current.text.length >= 420) {
                  pushSentence(ranges, current);
                  current = null;
                  pendingEnd = false;
                }
              }
              pushSentence(ranges, current);
              return ranges;
            }

            function isSentenceEnding(surface) {
              return /[.!?…]+$/u.test(surface || '');
            }

            function isClosingPunctuation(surface) {
              return /^[)\\]}»”’]+$/u.test(surface || '');
            }

            function pushSentence(ranges, range) {
              if (!range) return;
              const text = range.text.trim();
              if (!text) return;
              range.text = text;
              range.estimatedMs = estimateDurationMs(text);
              ranges.push(range);
            }

            function estimateDurationMs(text) {
              const charsPerSecond = 14;
              return Math.max(500, Math.round((Math.max(1, text.length) / charsPerSecond) * 1000));
            }

            async function toggleSpeech() {
              if (state.speech.mode === 'playing') {
                pauseSpeech();
                return;
              }
              if (state.speech.mode === 'paused' && state.speech.audio) {
                state.speech.mode = 'playing';
                updateSpeechButton();
                await state.speech.audio.play();
                startProgressUpdates();
                prefetchSentences(state.speech.sentenceIndex + 1);
                return;
              }
              await startSpeech();
            }

            async function startSpeech() {
              await flushEvents();
              if (!state.speech.ranges.length) {
                setSpeechStatus('На странице нет предложений для озвучки');
                return;
              }
              const focused = document.querySelector('.token:hover, .token.speech-focus, .token.visible');
              const focusIndex = focused ? Number(focused.dataset.index) : (state.tokens[0]?.index || 0);
              state.speech.sentenceIndex = Math.max(0, state.speech.ranges.findIndex(r => {
                const start = state.tokens[r.startToken]?.index;
                const end = state.tokens[r.endToken]?.index;
                return focusIndex >= start && focusIndex <= end;
              }));
              state.speech.mode = 'playing';
              updateSpeechButton();
              await playCurrentSentence();
            }

            function pauseSpeech() {
              state.speech.mode = 'paused';
              if (state.speech.audio) state.speech.audio.pause();
              stopProgressUpdates();
              updateSpeechButton();
              setSpeechStatus('Пауза');
            }

            function stopSpeech(clearStatus = true) {
              state.speech.mode = 'idle';
              stopProgressUpdates();
              if (state.speech.audio) {
                state.speech.audio.pause();
                state.speech.audio = null;
              }
              for (const cached of state.speech.cache.values()) {
                if (cached.url) URL.revokeObjectURL(cached.url);
              }
              state.speech.cache.clear();
              clearSpeechHighlights();
              updateSpeechButton();
              if (clearStatus) setSpeechStatus('');
            }

            async function playCurrentSentence() {
              if (state.speech.mode !== 'playing') return;
              const index = state.speech.sentenceIndex;
              const range = state.speech.ranges[index];
              if (!range) {
                stopSpeech();
                return;
              }
              highlightSentence(range, -1);
              scrollTokenIntoView(range.startToken);
              setSpeechStatus(`Готовлю Talgat: ${index + 1}/${state.speech.ranges.length}`);
              try {
                const prepared = await loadSentenceAudio(index);
                if (state.speech.mode !== 'playing') return;
                state.speech.audio = new Audio(prepared.url);
                state.speech.audio.addEventListener('ended', () => handleSentenceEnded(range));
                state.speech.audio.addEventListener('error', () => {
                  setSpeechStatus('Ошибка воспроизведения синтезированного звука');
                  pauseSpeech();
                });
                await state.speech.audio.play();
                startProgressUpdates();
                prefetchSentences(index + 1);
                enqueue(range.tokens.flatMap(t => tokenPayloads(t, 'token_tts_played')));
                setSpeechStatus(`Озвучивает Talgat: ${index + 1}/${state.speech.ranges.length}`);
              } catch (error) {
                state.speech.mode = 'idle';
                updateSpeechButton();
                clearSpeechHighlights();
                setSpeechStatus(error.message);
              }
            }

            function handleSentenceEnded(range) {
              stopProgressUpdates();
              highlightSentence(range, -1);
              state.speech.audio = null;
              if (state.speech.mode !== 'playing') return;
              state.speech.sentenceIndex++;
              if (state.speech.sentenceIndex >= state.speech.ranges.length) {
                stopSpeech();
              } else {
                playCurrentSentence();
              }
            }

            async function loadSentenceAudio(index) {
              if (state.speech.cache.has(index)) return state.speech.cache.get(index);
              const range = state.speech.ranges[index];
              const response = await fetch('/api/tts/speech', {
                method: 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({text: range.text, scope: 'sentence'})
              });
              if (!response.ok) {
                let message = 'RHVoice Talgat не настроен на сервере';
                try {
                  const body = await response.json();
                  if (body.message) message = body.message;
                } catch (_) {}
                throw new Error(message);
              }
              const blob = await response.blob();
              const prepared = {url: URL.createObjectURL(blob), blob, estimatedMs: range.estimatedMs};
              state.speech.cache.set(index, prepared);
              return prepared;
            }

            function prefetchSentences(fromIndex) {
              for (let offset = 0; offset < 2; offset++) {
                const index = fromIndex + offset;
                if (index >= state.speech.ranges.length || state.speech.cache.has(index)) continue;
                loadSentenceAudio(index).catch(() => {});
              }
            }

            function startProgressUpdates() {
              stopProgressUpdates();
              state.speech.progressTimer = setInterval(updateSpeechProgress, 60);
              updateSpeechProgress();
            }

            function stopProgressUpdates() {
              if (state.speech.progressTimer) {
                clearInterval(state.speech.progressTimer);
                state.speech.progressTimer = null;
              }
            }

            function updateSpeechProgress() {
              const audio = state.speech.audio;
              const range = state.speech.ranges[state.speech.sentenceIndex];
              if (!audio || !range) return;
              const durationMs = Number.isFinite(audio.duration) && audio.duration > 0
                ? audio.duration * 1000
                : range.estimatedMs;
              const elapsedMs = Math.max(0, audio.currentTime * 1000);
              const ratio = Math.max(0, Math.min(1, elapsedMs / Math.max(1, durationMs)));
              const tokenOffset = Math.min(range.endToken - range.startToken, Math.floor(ratio * Math.max(1, range.endToken - range.startToken + 1)));
              highlightSentence(range, range.startToken + tokenOffset);
            }

            function highlightSentence(range, focusToken) {
              clearSpeechHighlights();
              const sentenceTokens = [];
              for (let i = range.startToken; i <= range.endToken; i++) {
                const span = tokenNode(state.tokens[i]);
                if (!span) continue;
                span.classList.add('speech-sentence');
                sentenceTokens.push(state.tokens[i]);
              }
              range.tokens = sentenceTokens;
              const focus = tokenNode(state.tokens[focusToken]);
              if (focus) focus.classList.add('speech-focus');
            }

            function clearSpeechHighlights() {
              document.querySelectorAll('.speech-sentence,.speech-focus').forEach(node => {
                node.classList.remove('speech-sentence', 'speech-focus');
              });
            }

            function tokenNode(token) {
              if (!token) return null;
              return document.querySelector(`.token[data-index="${token.index}"]`);
            }

            function scrollTokenIntoView(localTokenIndex) {
              const node = tokenNode(state.tokens[localTokenIndex]);
              if (node) node.scrollIntoView({block: 'center', behavior: 'smooth'});
            }

            async function speakTokenWithServerTts(token) {
              if (!token) return;
              const morph = token.morphology || {};
              const text = [token.surface, morph.lemma, ...(token.translations || [])].filter(Boolean).join('. ');
              setSpeechStatus('Готовлю Talgat...');
              try {
                const response = await fetch('/api/tts/speech', {
                  method: 'POST',
                  credentials: 'same-origin',
                  headers: {'Content-Type': 'application/json'},
                  body: JSON.stringify({text, scope: 'token'})
                });
                if (!response.ok) {
                  const body = await response.json().catch(() => ({}));
                  throw new Error(body.message || 'RHVoice Talgat не настроен на сервере');
                }
                const blob = await response.blob();
                const audio = new Audio(URL.createObjectURL(blob));
                audio.addEventListener('ended', () => {
                  URL.revokeObjectURL(audio.src);
                  setSpeechStatus('');
                });
                await audio.play();
                enqueue(tokenPayloads(token, 'token_tts_played'));
                setSpeechStatus('Озвучивает Talgat');
              } catch (error) {
                setSpeechStatus(error.message);
              }
            }

            function updateSpeechButton() {
              const button = $('speakPage');
              const playing = state.speech.mode === 'playing';
              button.textContent = playing ? '⏸' : '▶';
              button.classList.toggle('playing', playing);
            }

            function setSpeechStatus(text) {
              $('speechStatus').textContent = text || '';
            }

            async function loadStats() {
              const data = await api('/api/reading/stats?limit=200');
              if (state.statsMode === 'features') {
                renderFeatureStats(data.features || []);
              } else {
                state.lastLemmaRows = data.lemmas || [];
                renderLemmaStats(state.lastLemmaRows);
              }
            }

            function renderLemmaStats(rows) {
              const content = $('statsContent');
              content.innerHTML = '<div class="stat-row header"><div>Лемма</div><div>Прочитано</div><div>Открыто</div><div class="wide-only">Озвучено</div><div class="wide-only">Видимость</div></div>';
              if (!rows.length) {
                content.insertAdjacentHTML('beforeend', '<p class="message">Пока нет сохраненной статистики.</p>');
                return;
              }
              for (const row of rows) {
                const element = document.createElement('div');
                element.className = 'stat-row clickable';
                const posLabel = formatPos(row.pos);
                element.innerHTML = `
                  <div class="stat-main">${escapeHtml(row.lemma)}<div class="stat-sub">${escapeHtml(posLabel)}</div></div>
                  <div>${row.committedCount}</div>
                  <div>${row.lookupCount}</div>
                  <div class="wide-only">${row.ttsCount}</div>
                  <div class="wide-only">${Math.round((row.totalVisibleMs || 0) / 1000)} с</div>`;
                element.addEventListener('click', () => loadLemmaTimeline(row));
                content.append(element);
              }
            }

            async function loadLemmaTimeline(row) {
              const content = $('statsContent');
              const url = `/api/reading/events?lemma=${encodeURIComponent(row.lemma)}&pos=${encodeURIComponent(row.pos)}&limit=5000`;
              const data = await api(url);
              renderLemmaTimeline(row, data.events || []);
              for (const existing of content.querySelectorAll('.stat-row.clickable.selected')) {
                existing.classList.remove('selected');
              }
            }

            function renderLemmaTimeline(row, events) {
              const content = $('statsContent');
              content.querySelector('.timeline-panel')?.remove();
              const panel = document.createElement('div');
              panel.className = 'timeline-panel';
              const min = events.reduce((value, event) => Math.min(value, event.occurredAtMs || value), events[0]?.occurredAtMs || Date.now());
              const max = events.reduce((value, event) => Math.max(value, event.occurredAtMs || value), min);
              const span = Math.max(1, max - min);
              const dots = events.map((event, index) => {
                const x = 24 + Math.round(((event.occurredAtMs - min) / span) * 732);
                const y = 36 + ((index % 3) - 1) * 10;
                const color = EVENT_COLORS[event.eventType] || '#7a8790';
                const title = `${event.eventType} · ${formatDate(event.occurredAtMs)} · стр. ${event.pageIndex + 1}`;
                return `<circle cx="${x}" cy="${y}" r="5" fill="${color}"><title>${escapeHtml(title)}</title></circle>`;
              }).join('');
              const legend = Object.entries(EVENT_COLORS)
                .filter(([type]) => events.some(event => event.eventType === type))
                .map(([type, color]) => `<span><i class="legend-dot" style="background:${color}"></i>${escapeHtml(labelEvent(type))}</span>`)
                .join('');
              panel.innerHTML = `
                <div class="timeline-title">
                  <strong>${escapeHtml(row.lemma)} · ${escapeHtml(formatPos(row.pos))}</strong>
                  <button type="button" id="timelineBack">К списку</button>
                </div>
                <svg class="timeline-svg" viewBox="0 0 780 72" preserveAspectRatio="none" role="img" aria-label="Временной ряд слова">
                  <line x1="24" y1="36" x2="756" y2="36" stroke="#ccd6d0" stroke-width="2" stroke-linecap="round"></line>
                  ${dots}
                </svg>
                <div class="timeline-axis"><span>${formatDate(min)}</span><span>${formatDate(max)}</span></div>
                <div class="timeline-legend">${legend || 'Нет событий для временного ряда'}</div>`;
              content.prepend(panel);
              $('timelineBack').addEventListener('click', () => panel.remove());
            }

            function labelEvent(type) {
              if (type === 'token_committed') return 'прочитано';
              if (type === 'token_exposed') return 'показано';
              if (type === 'token_lookup') return 'открыто';
              if (type === 'token_tts_played') return 'озвучено';
              return type;
            }

            function formatDate(ms) {
              if (!ms) return '—';
              return new Date(ms).toLocaleString('ru-RU', {day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit'});
            }

            function renderFeatureStats(rows) {
              const content = $('statsContent');
              content.innerHTML = '<div class="stat-row header"><div>Признак</div><div>Прочитано</div><div>Открыто</div><div class="wide-only">Показано</div><div class="wide-only">Видимость</div></div>';
              if (!rows.length) {
                content.insertAdjacentHTML('beforeend', '<p class="message">Пока нет статистики по признакам.</p>');
                return;
              }
              for (const row of rows) {
                const featureLabel = formatFeatureKey(row.featureKey);
                content.insertAdjacentHTML('beforeend', `
                  <div class="stat-row">
                    <div class="stat-main">${escapeHtml(featureLabel)}<div class="stat-sub">${escapeHtml(row.featureKey)}</div></div>
                    <div>${row.committedCount}</div>
                    <div>${row.lookupCount}</div>
                    <div class="wide-only">${row.exposureCount}</div>
                    <div class="wide-only">${Math.round((row.totalVisibleMs || 0) / 1000)} с</div>
                  </div>`);
              }
            }

            function escapeHtml(value) {
              return String(value).replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
            }

            $('authForm').addEventListener('submit', event => {
              event.preventDefault();
              authenticate('login');
            });
            $('registerButton').addEventListener('click', () => authenticate('register'));
            $('logoutButton').addEventListener('click', async () => {
              await flushEvents();
              await api('/api/auth/logout', {method: 'POST', body: '{}'});
              location.reload();
            });
            $('workSelect').addEventListener('change', async event => {
              state.workId = event.target.value;
              await loadPage(0);
            });
            $('prevPage').addEventListener('click', () => loadPage(state.pageIndex - 1));
            $('nextPage').addEventListener('click', () => loadPage(state.pageIndex + 1));
            $('closeSheet').addEventListener('click', () => $('tokenSheet').classList.add('hidden'));
            $('speakPage').addEventListener('click', () => {
              toggleSpeech();
            });
            $('speakToken').addEventListener('click', () => {
              if (!state.selectedToken) return;
              speakTokenWithServerTts(state.selectedToken);
            });
            $('statsButton').addEventListener('click', async () => {
              $('statsPanel').classList.remove('hidden');
              await loadStats();
            });
            $('closeStats').addEventListener('click', () => $('statsPanel').classList.add('hidden'));
            $('lemmaStatsTab').addEventListener('click', async () => {
              state.statsMode = 'lemmas';
              $('lemmaStatsTab').classList.add('active');
              $('featureStatsTab').classList.remove('active');
              await loadStats();
            });
            $('featureStatsTab').addEventListener('click', async () => {
              state.statsMode = 'features';
              $('featureStatsTab').classList.add('active');
              $('lemmaStatsTab').classList.remove('active');
              await loadStats();
            });
            document.addEventListener('visibilitychange', () => {
              if (document.hidden) {
                commitVisible(true);
                flushEvents(true);
                if (state.speech.mode === 'playing') pauseSpeech();
              }
            });
            window.addEventListener('pagehide', () => {
              commitVisible(true);
              flushEvents(true);
            });

            restoreQueue();
            loadMe().catch(() => {});
            setInterval(() => flushEvents(), 10000);
            """;
}

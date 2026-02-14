const WebSocket = require('ws');
const ws = new WebSocket('ws://localhost:9222/devtools/page/46B79CFD5EB4DE0C18119CCA2DDF2702');
ws.on('open', () => {
  const expr = '(function() {' +
    'var btn = document.getElementById("btnMenu");' +
    'var sidebar = document.getElementById("sidebar");' +
    'if(!btn) return "NO btnMenu FOUND";' +
    'if(!sidebar) return "NO sidebar FOUND";' +
    // Add touch+click debug listeners
    'btn.addEventListener("touchstart", function(e) { console.log("[HAMBURGER] touchstart on btnMenu, target=" + e.target.tagName + "#" + e.target.id + "." + e.target.className); }, true);' +
    'btn.addEventListener("touchend", function(e) { console.log("[HAMBURGER] touchend on btnMenu, target=" + e.target.tagName + "#" + e.target.id + "." + e.target.className); }, true);' +
    'btn.addEventListener("click", function(e) { console.log("[HAMBURGER] click on btnMenu, sidebar classes=" + sidebar.className); }, true);' +
    'document.addEventListener("touchstart", function(e) { ' +
    '  var rect = btn.getBoundingClientRect(); ' +
    '  var touch = e.touches[0]; ' +
    '  if(touch && touch.clientX >= rect.left - 20 && touch.clientX <= rect.right + 20 && touch.clientY >= rect.top - 20 && touch.clientY <= rect.bottom + 20) {' +
    '    console.log("[HAMBURGER] touch NEAR btnMenu at (" + touch.clientX + "," + touch.clientY + "), btn rect=(" + rect.left + "," + rect.top + "," + rect.right + "," + rect.bottom + "), target=" + e.target.tagName + "#" + e.target.id + "." + e.target.className);' +
    '  }' +
    '}, true);' +
    'document.addEventListener("click", function(e) { ' +
    '  if(sidebar.classList.contains("open")) {' +
    '    console.log("[HAMBURGER] document click while sidebar open, target=" + e.target.tagName + "#" + e.target.id + "." + e.target.className + " contains=" + (btn.contains(e.target) ? "YES btnMenu child" : "NOT btnMenu child"));' +
    '  }' +
    '}, false);' +
    'var cs = window.getComputedStyle(btn);' +
    'return JSON.stringify({' +
    '  status: "DEBUG LISTENERS INSTALLED",' +
    '  btnWidth: cs.width,' +
    '  btnHeight: cs.height,' +
    '  btnDisplay: cs.display,' +
    '  btnRect: btn.getBoundingClientRect(),' +
    '  sidebarClasses: sidebar.className,' +
    '  tokenSession: !!sessionStorage.getItem("authToken"),' +
    '  tokenLocal: !!localStorage.getItem("authToken")' +
    '});' +
    '})()';
  ws.send(JSON.stringify({
    id: 1,
    method: 'Runtime.evaluate',
    params: { expression: expr }
  }));
  // Also enable console API to capture console.log from page
  ws.send(JSON.stringify({
    id: 2,
    method: 'Runtime.enable'
  }));
  ws.send(JSON.stringify({
    id: 3,
    method: 'Log.enable'
  }));
});
ws.on('message', (data) => {
  const resp = JSON.parse(data.toString());
  if (resp.id === 1) {
    console.log('SETUP:', resp.result && resp.result.result ? resp.result.result.value : JSON.stringify(resp));
  }
  // Listen for console messages from the page
  if (resp.method === 'Runtime.consoleAPICalled') {
    const args = resp.params.args.map(a => a.value || a.description || JSON.stringify(a)).join(' ');
    if (args.includes('HAMBURGER')) {
      console.log('>>> CONSOLE:', args);
    }
  }
  if (resp.method === 'Log.entryAdded') {
    const text = resp.params.entry.text || '';
    if (text.includes('HAMBURGER')) {
      console.log('>>> LOG:', text);
    }
  }
});
ws.on('error', (err) => { console.error('WS Error:', err.message); });
// Keep running for 60 seconds to capture taps
setTimeout(() => { console.log('--- 60s monitoring done ---'); ws.close(); process.exit(0); }, 60000);

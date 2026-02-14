const WebSocket = require('ws');
const ws = new WebSocket('ws://localhost:9222/devtools/browser');
ws.on('open', () => {
  // Get list of targets
  ws.send(JSON.stringify({ id: 1, method: 'Target.getTargets' }));
});
ws.on('message', (data) => {
  const resp = JSON.parse(data.toString());
  console.log(JSON.stringify(resp, null, 2));
  if (resp.id === 1) {
    // Find the page target
    const targets = resp.result && resp.result.targetInfos || [];
    const page = targets.find(t => t.type === 'page');
    if (page) {
      console.log('Found page:', page.targetId, 'attached:', page.attached);
      // Attach to target
      ws.send(JSON.stringify({
        id: 2,
        method: 'Target.attachToTarget',
        params: { targetId: page.targetId, flatten: true }
      }));
    }
  }
  if (resp.id === 2) {
    console.log('Attached! SessionId:', resp.result && resp.result.sessionId);
    const sid = resp.result && resp.result.sessionId;
    if (sid) {
      // Enable Runtime events to capture console.log
      ws.send(JSON.stringify({ id: 10, sessionId: sid, method: 'Runtime.enable' }));
      // Install debug listeners on hamburger
      ws.send(JSON.stringify({
        id: 3,
        sessionId: sid,
        method: 'Runtime.evaluate',
        params: {
          expression: '(function() {' +
            'var btn = document.getElementById("btnMenu");' +
            'var sidebar = document.getElementById("sidebar");' +
            'if(!btn || !sidebar) return "MISSING: btn=" + !!btn + " sidebar=" + !!sidebar;' +
            'var cs = window.getComputedStyle(btn);' +
            'btn.addEventListener("touchstart", function(e) { console.log("[HAMBURGER] touchstart target=" + e.target.tagName + "#" + e.target.id + "." + e.target.className); }, true);' +
            'btn.addEventListener("touchend", function(e) { console.log("[HAMBURGER] touchend target=" + e.target.tagName + "#" + e.target.id + "." + e.target.className); }, true);' +
            'btn.addEventListener("click", function(e) { console.log("[HAMBURGER] CLICK! sidebar.classes=" + sidebar.className); }, true);' +
            'document.addEventListener("click", function(e) { ' +
            '  if(sidebar.classList.contains("open") || e.target === btn || btn.contains(e.target)) {' +
            '    console.log("[HAMBURGER] doc-click target=" + e.target.tagName + "#" + e.target.id + "." + e.target.className + " sidebar=" + sidebar.className + " btnContains=" + btn.contains(e.target));' +
            '  }' +
            '}, false);' +
            'return JSON.stringify({status:"LISTENERS_INSTALLED", btnWidth:cs.width, btnHeight:cs.height, btnDisplay:cs.display, btnRect:btn.getBoundingClientRect()});' +
            '})()'
        }
      }));
      global._sessionId = sid;
    }
  }
  if (resp.id === 3) {
    console.log('SETUP:', resp.result && resp.result.result ? resp.result.result.value : JSON.stringify(resp));
    console.log('\n=== TAP THE HAMBURGER BUTTON NOW (60s monitoring) ===\n');
  }
  // Capture console.log from the page
  if (resp.method === 'Runtime.consoleAPICalled') {
    const args = (resp.params.args || []).map(a => a.value || a.description || JSON.stringify(a)).join(' ');
    if (args.includes('HAMBURGER')) {
      console.log('>>>', new Date().toISOString().substr(11, 12), args);
    }
  }
});
ws.on('error', (err) => { console.error('Error:', err.message); });
setTimeout(() => { console.log('\n--- 60s monitoring done ---'); ws.close(); process.exit(0); }, 60000);

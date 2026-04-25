package com.signalbot.web

object Templates {
    val INDEX_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>SignalBot – Requesting Members</title>
  <style>
    * { box-sizing: border-box; }
    body { font-family: system-ui, -apple-system, sans-serif; max-width: 900px; margin: 0 auto; padding: 1.5rem; background: #1a1a2e; color: #eee; }
    h1 { margin-top: 0; }
    .refresh { margin-bottom: 1rem; }
    .refresh button { padding: 0.5rem 1rem; cursor: pointer; background: #4361ee; color: #fff; border: none; border-radius: 6px; }
    .refresh button:hover { background: #3a56d4; }
    table { width: 100%; border-collapse: collapse; background: #16213e; border-radius: 8px; overflow: hidden; }
    th, td { padding: 0.75rem 1rem; text-align: left; }
    th { background: #0f3460; }
    tr:nth-child(even) { background: #1a2a4a; }
    .status { display: inline-block; padding: 0.2rem 0.5rem; border-radius: 4px; font-size: 0.85rem; }
    .status.messaged { background: #2e7d32; color: #fff; }
    .status.not_messaged { background: #666; color: #fff; }
    .status.pending { background: #555; color: #fff; }
    .status.vetting_sent { background: #1565c0; color: #fff; }
    .status.vetting_followup_sent { background: #0d47a1; color: #fff; }
    .status.welcome_sent { background: #2e7d32; color: #fff; }
    .status.filter_skipped { background: #b45309; color: #fff; }
    .welcome-btn { padding: 0.4rem 0.8rem; cursor: pointer; background: #4361ee; color: #fff; border: none; border-radius: 6px; font-weight: 500; margin-right: 0.4rem; }
    .welcome-btn:hover { background: #3a56d4; }
    .welcome-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .approve-btn { padding: 0.4rem 0.8rem; cursor: pointer; background: #06d6a0; color: #111; border: none; border-radius: 6px; font-weight: 600; }
    .approve-btn:hover { background: #05c090; }
    .approve-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .deny-btn { padding: 0.4rem 0.8rem; cursor: pointer; background: #ef476f; color: #fff; border: none; border-radius: 6px; font-weight: 500; margin-left: 0.2rem; }
    .deny-btn:hover { background: #d63d62; }
    .deny-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .actions-cell { white-space: nowrap; }
    .error { color: #ff6b6b; margin-top: 0.5rem; }
    .message { margin-bottom: 1rem; padding: 0.75rem; border-radius: 6px; }
    .message.info { background: #0f3460; }
    .message.error { background: #5c1010; }
    .message.success { background: #1b4332; color: #e8f5e9; }
  </style>
</head>
<body>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:1rem;">
    <h1 style="margin:0;">Requesting Members</h1>
    <a href="/logout" style="color:#9bb1ff;">Log out</a>
  </div>
  <p class="message info">Send welcome: sends the rules message (approve_rules_message) only. Approve: approves in the main group and optionally adds to additional groups (approve_add_to_group_id, approve_add_to_group_id_2). Deny: denies the join request. Names are loaded from Signal when available; add <code>?debug=1</code> to the URL for name lookup debug.</p>
  <p id="group-names" class="message info" style="display:none;"></p>
  <div class="refresh">
    <button type="button" id="refresh">Refresh list</button>
  </div>
  <div id="error" class="error" style="display:none;"></div>
  <p id="success" class="message success" style="display:none;"></p>
  <div id="debug-wrap"></div>
  <table>
    <thead>
      <tr>
        <th>Name</th>
        <th>Number / UUID</th>
        <th>Pipeline</th>
        <th>Last activity</th>
        <th>Actions</th>
      </tr>
    </thead>
    <tbody id="tbody">
      <tr><td colspan="5">Loading…</td></tr>
    </tbody>
  </table>

  <script>
    const tbody = document.getElementById('tbody');
    const errEl = document.getElementById('error');
    const successEl = document.getElementById('success');
    var successTimer = null;

    function showError(msg) {
      errEl.textContent = msg;
      errEl.style.display = msg ? 'block' : 'none';
    }

    function showSuccess(msg) {
      if (successTimer) { clearTimeout(successTimer); successTimer = null; }
      if (!msg) {
        successEl.textContent = '';
        successEl.style.display = 'none';
        return;
      }
      successEl.textContent = msg;
      successEl.style.display = 'block';
      successTimer = setTimeout(function() {
        successEl.style.display = 'none';
        successEl.textContent = '';
        successTimer = null;
      }, 5000);
    }

    function load() {
      showError('');
      showSuccess('');
      const debugWrap = document.getElementById('debug-wrap');
      if (debugWrap) debugWrap.innerHTML = '';
      tbody.innerHTML = '<tr><td colspan="5">Loading…</td></tr>';
      const debug = new URLSearchParams(window.location.search).get('debug');
      const url = '/api/requesting?_=' + Date.now() + (debug ? '&debug=1' : '');
      fetch(url, { cache: 'no-store' })
        .then(r => r.ok ? r.json() : Promise.reject(r))
        .then(data => {
          if (data.error) {
            showError(data.error);
            tbody.innerHTML = '<tr><td colspan="5">Error loading list.</td></tr>';
            return;
          }
          const groupNamesEl = document.getElementById('group-names');
          if (groupNamesEl && (data.main_group_name !== undefined || (data.add_group_names && data.add_group_names.length))) {
            let msg = 'Approve adds to main group: <strong>' + escapeHtml(data.main_group_name || '?') + '</strong>';
            if (data.add_group_names && data.add_group_names.length) {
              data.add_group_names.forEach(function(name, i) {
                msg += ' · Then adds to group ' + (i + 2) + ': <strong>' + escapeHtml(name || '?') + '</strong>';
              });
            }
            msg += '. Check config.yaml (group_id, approve_add_to_group_id, approve_add_to_group_id_2) if these are wrong.';
            groupNamesEl.innerHTML = msg;
            groupNamesEl.style.display = 'block';
          }
          const list = data.requesting || [];
          if (list.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5">No requesting members.</td></tr>';
            return;
          }
          function postMember(url, member, btn, onSuccess) {
            btn.disabled = true;
            fetch(url, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ member: { uuid: member.uuid || undefined, number: member.number || undefined } })
            })
              .then(r => r.json())
              .then(data => {
                if (data.error) {
                  showError(data.error);
                  btn.disabled = false;
                  return;
                }
                showError('');
                if (onSuccess) onSuccess(data);
                btn.disabled = false;
              })
              .catch(() => { showError('Request failed'); btn.disabled = false; });
          }
          function displayName(m) {
            // Signal lets users join groups without setting a profile name.
            // When the fetched profile is empty we get the raw UUID back from
            // the server - render it as "Guest <first 8>" so each anonymous
            // requester is still visually distinct but the full UUID moves
            // to the dedicated Number / UUID column.
            const raw = (m.name || '').trim();
            const uuid = (m.uuid || '').trim();
            const isPlaceholder = !raw || raw === uuid || raw === (m.number || '').trim();
            if (!isPlaceholder) return raw;
            if (uuid) return 'Guest ' + uuid.slice(0, 8);
            return '—';
          }
          function pipelineLabel(m) {
            if (m.intake_label) return m.intake_label;
            return m.status === 'messaged' ? 'Messaged' : 'Not messaged';
          }
          function pipelineClass(m) {
            return m.intake_state || (m.status === 'messaged' ? 'messaged' : 'not_messaged');
          }
          tbody.innerHTML = list.map(m => `
            <tr data-uuid="${'$'}{escapeHtml(m.uuid || '')}" data-number="${'$'}{escapeHtml(m.number || '')}">
              <td>${'$'}{escapeHtml(displayName(m))}</td>
              <td>${'$'}{escapeHtml(m.number || m.uuid || '—')}</td>
              <td><span class="status ${'$'}{pipelineClass(m)}" title="${'$'}{escapeHtml(pipelineLabel(m))}">${'$'}{escapeHtml(pipelineLabel(m))}</span></td>
              <td>${'$'}{escapeHtml(m.messaged_at || '—')}</td>
              <td class="actions-cell">
                <button class="welcome-btn" type="button">Send welcome</button>
                <button class="approve-btn" type="button">Approve</button>
                <button class="deny-btn" type="button">Deny</button>
              </td>
            </tr>
          `).join('');
          tbody.querySelectorAll('.welcome-btn').forEach(btn => {
            btn.addEventListener('click', function() {
              const row = this.closest('tr');
              postMember('/api/send-welcome', { uuid: row.dataset.uuid, number: row.dataset.number }, this, function(data) {
                const st = row.querySelector('.status');
                const ic = (data && data.intake_state) ? data.intake_state : 'welcome_sent';
                const il = (data && data.intake_label) ? data.intake_label : 'Welcome / rules sent';
                if (st) { st.className = 'status ' + ic; st.textContent = il; st.title = il; }
                const tds = row.querySelectorAll('td');
                if (tds.length >= 4) {
                  tds[3].textContent = new Date().toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'medium' });
                }
                showSuccess('Welcome / rules message sent. They should receive a DM on Signal.');
              });
            });
          });
          tbody.querySelectorAll('.approve-btn').forEach(btn => {
            btn.addEventListener('click', function() {
              const row = this.closest('tr');
              postMember('/api/approve', { uuid: row.dataset.uuid, number: row.dataset.number }, this, function() {
                row.remove();
              });
            });
          });
          tbody.querySelectorAll('.deny-btn').forEach(btn => {
            btn.addEventListener('click', function() {
              const row = this.closest('tr');
              postMember('/api/deny', { uuid: row.dataset.uuid, number: row.dataset.number }, this, function() {
                row.remove();
              });
            });
          });
          if (data.name_lookup_debug) {
            const pre = document.createElement('pre');
            pre.className = 'message';
            pre.style.marginTop = '1rem';
            pre.style.fontSize = '0.85rem';
            pre.textContent = 'Name lookup debug: ' + JSON.stringify(data.name_lookup_debug, null, 2);
            const wrap = document.getElementById('debug-wrap');
            if (wrap) { wrap.appendChild(pre); }
          }
        })
        .catch(() => { showError('Failed to load'); tbody.innerHTML = '<tr><td colspan="5">Failed to load.</td></tr>'; });
    }

    function escapeHtml(s) {
      const div = document.createElement('div');
      div.textContent = s;
      return div.innerHTML;
    }

    document.getElementById('refresh').addEventListener('click', load);
    load();
  </script>
</body>
</html>
"""

    fun loginHtml(error: String? = null): String {
        val errorHtml = if (!error.isNullOrBlank()) {
            """<div class="error">${escape(error)}</div>"""
        } else ""
        return """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>SignalBot Admin Login</title>
  <style>
    * { box-sizing: border-box; }
    body { font-family: system-ui, -apple-system, sans-serif; background:#101827; color:#eef2ff; min-height:100vh; margin:0; display:grid; place-items:center; padding:1rem; }
    .card { width:100%; max-width:420px; background:#1f2937; border-radius:12px; padding:1.25rem; box-shadow:0 8px 30px rgba(0,0,0,.25); }
    h1 { margin-top:0; font-size:1.2rem; }
    label { display:block; margin-top:.75rem; margin-bottom:.35rem; color:#c7d2fe; font-size:.95rem; }
    input { width:100%; padding:.6rem .7rem; border-radius:8px; border:1px solid #374151; background:#111827; color:#eef2ff; }
    button { width:100%; margin-top:1rem; padding:.65rem .8rem; border:none; border-radius:8px; cursor:pointer; background:#4f46e5; color:white; font-weight:600; }
    button:hover { background:#4338ca; }
    .error { margin-top:.75rem; color:#fca5a5; }
    .hint { margin-top:.75rem; font-size:.85rem; color:#9ca3af; }
  </style>
</head>
<body>
  <form class="card" method="post" action="/login">
    <h1>SignalBot Admin</h1>
    <label for="username">Username</label>
    <input id="username" name="username" type="text" autocomplete="username" required>
    <label for="password">Password</label>
    <input id="password" name="password" type="password" autocomplete="current-password" required>
    <button type="submit">Sign in</button>
    $errorHtml
    <div class="hint">Set SIGNALBOT_ADMIN_USERNAME and SIGNALBOT_ADMIN_PASSWORD_HASH in your host environment.</div>
  </form>
</body>
</html>
"""
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

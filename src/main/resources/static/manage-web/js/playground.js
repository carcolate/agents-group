let pollTimer = null;

window.addEventListener('DOMContentLoaded', loadAgents);

async function loadAgents() {
    const rsp = await API.get('/manage/agent/list', { pageNum: 1, pageSize: 200, status: 1 });
    if (rsp.code !== 0) { toast(rsp.msg || '加载 Agent 失败', true); return; }
    const sel = document.getElementById('p_agent_id');
    const list = (rsp.data || []);
    sel.innerHTML = list.map(a => `<option value="${a.id}">${escapeHtml(a.name)}</option>`).join('');
    if (!list.length) {
        toast('请先在 Agent 管理中新增并启用至少一个 Agent', true);
    }
}

async function sendChat() {
    const agentId = document.getElementById('p_agent_id').value;
    const current = document.getElementById('p_current').value.trim();
    if (!agentId || !current) {
        toast('请选择 Agent 并填写当前消息', true);
        return;
    }
    const summary = document.getElementById('p_summary').value.trim();
    const historyText = document.getElementById('p_history').value;
    const historyMessages = historyText.split('\n').map(s => s.trim()).filter(s => s.length > 0);

    document.getElementById('steps').innerHTML = '';
    document.getElementById('final').classList.add('hide');
    document.getElementById('p_uuid').textContent = '';
    document.getElementById('p_status').textContent = '提交中...';

    const rsp = await API.post('/copilot/chat', {
        agentId: Number(agentId),
        currentMessage: current,
        historyMessages,
        historySummary: summary
    });
    if (rsp.code !== 0 || !rsp.data || !rsp.data.uuid) {
        document.getElementById('p_status').textContent = '';
        toast(rsp.msg || '提交失败', true);
        return;
    }
    const uuid = rsp.data.uuid;
    document.getElementById('p_uuid').textContent = uuid;
    document.getElementById('p_status').textContent = '任务运行中...';
    startPolling(uuid);
}

function startPolling(uuid) {
    if (pollTimer) clearInterval(pollTimer);
    let stoppedAt = null;
    pollTimer = setInterval(async () => {
        const rsp = await API.get('/copilot/chat/result', { uuid });
        if (rsp.code !== 0) return;
        const snap = rsp.data;
        renderSteps(snap);
        if (snap.status === 'SUCCESS' || snap.status === 'FAILED') {
            if (stoppedAt == null) stoppedAt = Date.now();
            if (Date.now() - stoppedAt > 800) {
                clearInterval(pollTimer);
                pollTimer = null;
            }
        }
    }, 800);
}

function renderSteps(snap) {
    document.getElementById('p_status').textContent = '状态：' + snap.status
        + (snap.errorMsg ? ' / ' + snap.errorMsg : '');
    const wrap = document.getElementById('steps');
    const steps = snap.steps || [];
    const reversed = [...steps].reverse();
    wrap.innerHTML = reversed.map((s, i) => `
        <div class="step step-${s.type}">
            <div class="head">
                <span class="type">${s.type}${s.toolName ? ' · ' + escapeHtml(s.toolName) : ''}</span>
                <span class="time">${formatTime(s.time)}</span>
            </div>
            <div class="step-content">
                <pre>${escapeHtml(s.content || '')}</pre>
                ${s.toolArgs ? `<pre class="mono">args: ${escapeHtml(s.toolArgs)}</pre>` : ''}
            </div>
            <span class="show-all-btn hide" onclick="showFullStep(this)">... 显示全部</span>
        </div>
    `).join('');
    wrap.querySelectorAll('.step-content').forEach(el => {
        if (el.scrollHeight > el.clientHeight) {
            el.parentNode.querySelector('.show-all-btn').classList.remove('hide');
        }
    });
    wrap.scrollTop = 0;
    if (snap.status === 'SUCCESS' && snap.finalReply) {
        document.getElementById('final').classList.remove('hide');
        document.getElementById('final_text').textContent = snap.finalReply;
    }
}

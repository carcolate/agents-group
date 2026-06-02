let pollTimer = null;
let currentUuid = null;

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
    const historyMessages = parseHistoryMessages(historyText);

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
    currentUuid = uuid;
    document.getElementById('p_uuid').textContent = uuid;
    document.getElementById('p_status').textContent = '任务运行中...';
    document.getElementById('p_cancel_btn').style.display = '';
    startPolling(uuid);
}

async function cancelChat() {
    if (!currentUuid) return;
    const btn = document.getElementById('p_cancel_btn');
    btn.disabled = true;
    btn.textContent = '正在中断…';
    const rsp = await API.post('/copilot/chat/cancel', null, { uuid: currentUuid });
    btn.disabled = false;
    btn.textContent = '中断当前任务';
    if (rsp.code !== 0) {
        toast(rsp.msg || '中断失败', true);
        return;
    }
    toast('已发出中断请求，等待当前轮 LLM 调用结束');
}

function startPolling(uuid) {
    if (pollTimer) clearInterval(pollTimer);
    let stoppedAt = null;
    pollTimer = setInterval(async () => {
        const rsp = await API.get('/copilot/chat/result', { uuid });
        if (rsp.code !== 0) return;
        const snap = rsp.data;
        renderSteps(snap);
        if (snap.status === 'SUCCESS' || snap.status === 'FAILED' || snap.status === 'CANCELLED') {
            if (stoppedAt == null) stoppedAt = Date.now();
            if (Date.now() - stoppedAt > 800) {
                clearInterval(pollTimer);
                pollTimer = null;
                document.getElementById('p_cancel_btn').style.display = 'none';
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
                <span class="type">${s.type}${s.toolName ? ' · ' + escapeHtml(s.toolName) : ''}${s.thinking ? ' · <span class="thinking-flag">含思考链</span>' : ''}</span>
                <span class="time">${formatTime(s.time)}</span>
            </div>
            ${renderThinkingBlock(s.thinking)}
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

/**
 * 把 textarea 文本按 "角色|时间|内容|图片URL" 解析为 HistoryMessage 数组。
 * 宽松处理：
 *   - 1 段（无竖线）→ role=未知，content=原文，time/image 留空
 *   - 2 段           → role | content（time/image 留空）
 *   - 3 段           → role | time | content（image 留空）
 *   - 4 段及以上     → role | time | content | image（第 4 段后整体拼回 image，
 *                       兼容 URL 里出现的 query string 等）
 */
function parseHistoryMessages(text) {
    if (!text) return [];
    return text.split('\n')
        .map(s => s.trim())
        .filter(s => s.length > 0)
        .map(line => {
            const parts = line.split('|');
            if (parts.length === 1) {
                return { role: '未知', content: parts[0].trim(), time: '', image: '' };
            }
            if (parts.length === 2) {
                return { role: parts[0].trim(), content: parts[1].trim(), time: '', image: '' };
            }
            if (parts.length === 3) {
                return {
                    role: parts[0].trim(),
                    time: parts[1].trim(),
                    content: parts[2].trim(),
                    image: ''
                };
            }
            return {
                role: parts[0].trim(),
                time: parts[1].trim(),
                content: parts[2].trim(),
                image: parts.slice(3).join('|').trim()
            };
        });
}

function renderThinkingBlock(thinking) {
    if (!thinking) return '';
    return `
        <details class="thinking-block">
            <summary>💭 LLM 思考过程（点击展开）</summary>
            <pre>${escapeHtml(thinking)}</pre>
        </details>
    `;
}

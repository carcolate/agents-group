let historyPage = { pageNum: 1, pageSize: 20, total: 0 };
let agentOptions = [];

window.addEventListener('DOMContentLoaded', async () => {
    await loadAgents();
    loadList();
});

async function loadAgents() {
    const rsp = await API.get('/manage/agent/list', { pageNum: 1, pageSize: 200 });
    if (rsp.code !== 0) return;
    agentOptions = rsp.data || [];
    const filter = document.getElementById('filterAgent');
    filter.innerHTML = '<option value="">全部 Agent</option>' +
        agentOptions.map(a => `<option value="${a.id}">${escapeHtml(a.name)}</option>`).join('');
}

function agentName(id, fallback) {
    const a = agentOptions.find(x => String(x.id) === String(id));
    return a ? a.name : (fallback || ('#' + id));
}

async function loadList() {
    const params = {
        pageNum: historyPage.pageNum,
        pageSize: historyPage.pageSize
    };
    const aid = document.getElementById('filterAgent').value;
    if (aid) params.agentId = aid;
    const st = document.getElementById('filterStatus').value;
    if (st) params.status = st;
    const kw = document.getElementById('searchKeyword').value.trim();
    if (kw) params.userMessage = kw;
    const uuid = document.getElementById('searchUuid').value.trim();
    if (uuid) params.uuid = uuid;
    const rsp = await API.get('/manage/copilot/history/list', params);
    if (rsp.code !== 0) { toast(rsp.msg || '加载失败', true); return; }
    renderList(rsp.data || [], rsp.total || 0);
}

function statusBadge(s) {
    if (s === 'SUCCESS') return '<span class="tag tag-on">SUCCESS</span>';
    if (s === 'FAILED') return '<span class="tag tag-off">FAILED</span>';
    return '<span class="tag">' + escapeHtml(s || '-') + '</span>';
}

function truncate(s, n) {
    if (s == null) return '';
    const str = String(s);
    return str.length > n ? str.slice(0, n) + '…' : str;
}

function formatCost(ms) {
    if (ms == null) return '-';
    const n = Number(ms);
    if (n < 1000) return n + 'ms';
    return (n / 1000).toFixed(2) + 's';
}

function renderList(rows, total) {
    historyPage.total = total;
    const tbody = document.getElementById('tbody');
    if (!rows.length) {
        tbody.innerHTML = '<tr><td colspan="9" style="text-align:center;color:#95a5a6;">暂无数据</td></tr>';
    } else {
        tbody.innerHTML = rows.map(r => `
            <tr>
                <td><span class="mono">${escapeHtml(formatTime(r.createdAt))}</span></td>
                <td>${escapeHtml(agentName(r.agentId, r.agentName))}</td>
                <td title="${escapeHtml(r.userMessage || '')}">${escapeHtml(truncate(r.userMessage, 60))}</td>
                <td title="${escapeHtml(r.finalReply || '')}">${escapeHtml(truncate(r.finalReply, 80))}</td>
                <td>${statusBadge(r.status)}</td>
                <td>${r.llmRound == null ? '-' : r.llmRound}</td>
                <td>${r.stepCount == null ? '-' : r.stepCount}</td>
                <td>${formatCost(r.costMs)}</td>
                <td class="ops">
                    <button onclick='openDetail(${JSON.stringify(r.uuid)})'>详情</button>
                </td>
            </tr>
        `).join('');
    }
    document.getElementById('total').textContent = total;
    const maxPage = Math.max(1, Math.ceil(total / historyPage.pageSize));
    document.getElementById('pageInfo').textContent = `${historyPage.pageNum} / ${maxPage}`;
}

function firstPage() {
    historyPage.pageNum = 1;
    loadList();
}
function prevPage() {
    if (historyPage.pageNum <= 1) return;
    historyPage.pageNum--;
    loadList();
}
function nextPage() {
    const maxPage = Math.max(1, Math.ceil(historyPage.total / historyPage.pageSize));
    if (historyPage.pageNum >= maxPage) return;
    historyPage.pageNum++;
    loadList();
}
function changePageSize() {
    const v = Number(document.getElementById('pageSizeSel').value) || 20;
    historyPage.pageSize = v;
    historyPage.pageNum = 1;
    loadList();
}

async function openDetail(uuid) {
    document.getElementById('detailMeta').textContent = 'UUID: ' + uuid + ' · 正在从 Redis 加载…';
    document.getElementById('detailFinal').classList.add('hide');
    document.getElementById('detailFinalText').textContent = '';
    document.getElementById('detailSteps').innerHTML = '';
    document.getElementById('detailEmpty').style.display = 'none';
    showModal('detailModal');

    const rsp = await API.get('/manage/copilot/history/detail', { uuid });
    if (rsp.code !== 0) {
        document.getElementById('detailMeta').textContent = 'UUID: ' + uuid;
        document.getElementById('detailEmpty').style.display = '';
        return;
    }
    const snap = rsp.data || {};
    document.getElementById('detailMeta').innerHTML =
        `UUID: <span class="mono">${escapeHtml(snap.uuid || uuid)}</span> · `
        + `Agent: ${escapeHtml(snap.agentName || ('#' + (snap.agentId || '?')))} · `
        + `状态: ${escapeHtml(snap.status || '-')} · `
        + `开始: ${escapeHtml(formatTime(snap.createdAt))} · `
        + `结束: ${escapeHtml(formatTime(snap.finishedAt))}`
        + (snap.errorMsg ? ` · <span style="color:#e74c3c;">${escapeHtml(snap.errorMsg)}</span>` : '');

    if (snap.finalReply) {
        document.getElementById('detailFinal').classList.remove('hide');
        document.getElementById('detailFinalText').textContent = snap.finalReply;
    }
    const steps = snap.steps || [];
    const wrap = document.getElementById('detailSteps');
    wrap.innerHTML = steps.map(s => `
        <div class="step step-${s.type}">
            <div class="head">
                <span class="type">${escapeHtml(s.type)}${s.toolName ? ' · ' + escapeHtml(s.toolName) : ''}${s.thinking ? ' · <span class="thinking-flag">含思考链</span>' : ''}</span>
                <span class="time">${escapeHtml(formatTime(s.time))}</span>
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
}

function closeDetail() { hideModal('detailModal'); }

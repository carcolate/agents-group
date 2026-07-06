let ragPage = { pageNum: 1, pageSize: 20, total: 0 };
let agentOptions = [];

window.addEventListener('DOMContentLoaded', async () => {
    await loadAgents();
    initDragDrop();
});

async function loadAgents() {
    const rsp = await API.get('/manage/agent/list', { pageNum: 1, pageSize: 200 });
    if (rsp.code !== 0) { toast(rsp.msg || '加载 Agent 失败', true); return; }
    agentOptions = rsp.data || [];
    const filter = document.getElementById('filterAgent');
    filter.innerHTML = '<option value="">请选择 Agent</option>' +
        agentOptions.map(a => `<option value="${a.id}">${escapeHtml(a.name)}</option>`).join('');
    const f = document.getElementById('f_agent_id');
    f.innerHTML = agentOptions.map(a => `<option value="${a.id}">${escapeHtml(a.name)}</option>`).join('');
}

async function loadList() {
    const aid = document.getElementById('filterAgent').value;
    if (!aid) {
        document.getElementById('tbody').innerHTML = '<tr><td colspan="7" style="text-align:center;color:#95a5a6;">请先选择 Agent</td></tr>';
        document.getElementById('total').textContent = '0';
        document.getElementById('pageInfo').textContent = '1 / 1';
        return;
    }
    const params = {
        pageNum: ragPage.pageNum,
        pageSize: ragPage.pageSize,
        agentId: aid
    };
    const kw = document.getElementById('searchTitle').value.trim();
    if (kw) params.title = kw;
    const rsp = await API.get('/manage/rag/list', params);
    if (rsp.code !== 0) { toast(rsp.msg || '加载失败', true); return; }
    renderList(rsp.data || [], rsp.total || 0);
}

function onAgentChange() {
    ragPage.pageNum = 1;
    loadList();
}

function agentName(id) {
    const a = agentOptions.find(x => String(x.id) === String(id));
    return a ? a.name : ('#' + id);
}

function engageTag(t) {
    if (t === 2) return '<span class="tag tag-prepend">前置注入</span>';
    return '<span class="tag tag-rag">AI 自检索</span>';
}

function renderList(rows, total) {
    ragPage.total = total;
    const tbody = document.getElementById('tbody');
    if (!rows.length) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;color:#95a5a6;">暂无数据</td></tr>';
    } else {
        tbody.innerHTML = rows.map(r => {
            const sum = r.summary || '';
            const sumShort = sum.length > 80 ? sum.slice(0, 80) + '…' : sum;
            const et = r.engageType == null ? 1 : Number(r.engageType);
            const sumCell = et === 2
                ? '<span style="color:#95a5a6;">（前置注入，无需摘要）</span>'
                : (escapeHtml(sumShort) || '<span style="color:#95a5a6;">（未生成）</span>');
            return `
            <tr>
                <td><span class="mono">${escapeHtml(r.id)}</span></td>
                <td>${escapeHtml(agentName(r.agentId))}</td>
                <td>${escapeHtml(r.title)}</td>
                <td>${engageTag(et)}</td>
                <td title="${escapeHtml(sum)}">${sumCell}</td>
                <td>${statusTag(r.status)}</td>
                <td class="ops">
                    <button onclick='openEditor(${JSON.stringify(r.id)})'>编辑</button>
                    <button class="danger" onclick='delRag(${JSON.stringify(r.id)})'>删除</button>
                </td>
            </tr>
        `;
        }).join('');
    }
    document.getElementById('total').textContent = total;
    const maxPage = Math.max(1, Math.ceil(total / ragPage.pageSize));
    document.getElementById('pageInfo').textContent = `${ragPage.pageNum} / ${maxPage}`;
}

function prevPage() {
    if (ragPage.pageNum <= 1) return;
    ragPage.pageNum--;
    loadList();
}
function nextPage() {
    const maxPage = Math.max(1, Math.ceil(ragPage.total / ragPage.pageSize));
    if (ragPage.pageNum >= maxPage) return;
    ragPage.pageNum++;
    loadList();
}

async function openEditor(id) {
    document.getElementById('editorTitle').textContent = id ? '编辑文档' : '新增文档';
    document.getElementById('f_id').value = '';
    document.getElementById('f_title').value = '';
    document.getElementById('f_content').value = '';
    document.getElementById('f_summary').value = '';
    document.getElementById('f_status').value = '1';
    document.getElementById('f_engage_type').value = '1';
    const filterAid = document.getElementById('filterAgent').value;
    if (filterAid) document.getElementById('f_agent_id').value = filterAid;
    if (id) {
        const rsp = await API.get('/manage/rag/get', { id });
        if (rsp.code !== 0) { toast(rsp.msg || '加载失败', true); return; }
        const r = rsp.data;
        document.getElementById('f_id').value = r.id;
        document.getElementById('f_agent_id').value = r.agentId;
        document.getElementById('f_title').value = r.title || '';
        document.getElementById('f_content').value = r.content || '';
        document.getElementById('f_summary').value = r.summary || '';
        document.getElementById('f_status').value = r.status == null ? '1' : String(r.status);
        document.getElementById('f_engage_type').value = r.engageType == null ? '1' : String(r.engageType);
    }
    onEngageTypeChange();
    showModal('editor');
}

function onEngageTypeChange() {
    const t = document.getElementById('f_engage_type').value;
    const desc = document.getElementById('engage_desc');
    const sumRow = document.getElementById('f_summary').closest('.form-row');
    if (t === '2') {
        desc.textContent = '前置：不切片不向量化，每次对话会把全文自动拼到 systemPrompt 给 LLM。适合规则手册、价格表、政策强约束等必须每次都看到的内容。';
        if (sumRow) sumRow.style.display = 'none';
    } else {
        desc.textContent = '默认：保存时自动切片向量化，由 LLM 通过工具按需检索。';
        if (sumRow) sumRow.style.display = '';
    }
}

function closeEditor() { hideModal('editor'); }

async function submitForm() {
    const id = document.getElementById('f_id').value;
    const engageType = Number(document.getElementById('f_engage_type').value) || 1;
    const body = {
        agentId: document.getElementById('f_agent_id').value,
        title: document.getElementById('f_title').value.trim(),
        content: document.getElementById('f_content').value,
        summary: engageType === 2 ? null : (document.getElementById('f_summary').value.trim() || null),
        engageType: engageType,
        status: Number(document.getElementById('f_status').value)
    };
    if (!body.agentId || !body.title || !body.content) {
        toast('Agent / 标题 / 内容均必填', true);
        return;
    }
    document.getElementById('loadingText').textContent = engageType === 2
        ? '保存中，正在重建向量库…'
        : '保存中，正在生成摘要，请稍候…';
    document.getElementById('loadingOverlay').classList.remove('hide');
    let rsp;
    if (id) {
        body.id = id;
        rsp = await API.post('/manage/rag/update', body);
    } else {
        rsp = await API.post('/manage/rag/add', body);
    }
    document.getElementById('loadingOverlay').classList.add('hide');
    const okMsg = engageType === 2
        ? ((id ? '已更新' : '已新增') + '（前置知识库，已纳入 systemPrompt）')
        : ((id ? '已更新' : '已新增') + '，摘要已生成，向量库正在异步重建');
    if (tip(rsp, okMsg)) {
        closeEditor();
        loadList();
    }
}

async function delRag(id) {
    if (!confirm('确认删除该文档？删除后会重建对应 Agent 的向量库。')) return;
    const rsp = await API.post('/manage/rag/delete', null, { id });
    if (tip(rsp, '已删除')) loadList();
}

async function rebuildAgent() {
    const aid = document.getElementById('filterAgent').value;
    if (!aid) { toast('请先在左侧筛选选中具体 Agent', true); return; }
    const rsp = await API.post('/manage/rag/rebuild', null, { agentId: aid });
    tip(rsp, '已触发重建');
}

function initDragDrop() {
    const ta = document.getElementById('f_content');
    ta.addEventListener('dragover', e => {
        e.preventDefault();
        ta.style.borderColor = '#2364c8';
        ta.style.background = '#f0f6ff';
    });
    ta.addEventListener('dragleave', () => {
        ta.style.borderColor = '';
        ta.style.background = '';
    });
    ta.addEventListener('drop', e => {
        e.preventDefault();
        ta.style.borderColor = '';
        ta.style.background = '';
        const files = e.dataTransfer.files;
        if (!files.length) return;
        const file = files[0];
        if (!file.name.toLowerCase().endsWith('.md') && !file.name.toLowerCase().endsWith('.txt')) {
            toast('仅支持 .md 或 .txt 文件', true);
            return;
        }
        const reader = new FileReader();
        reader.onload = () => {
            ta.value = reader.result;
            ta.dispatchEvent(new Event('input'));
        };
        reader.readAsText(file, 'UTF-8');
    });
}

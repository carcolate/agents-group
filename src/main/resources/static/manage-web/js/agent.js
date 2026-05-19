let agentPage = { pageNum: 1, pageSize: 20, total: 0 };

window.addEventListener('DOMContentLoaded', loadList);

async function loadList() {
    const params = {
        pageNum: agentPage.pageNum,
        pageSize: agentPage.pageSize
    };
    const kw = document.getElementById('searchName').value.trim();
    if (kw) params.name = kw;
    const rsp = await API.get('/manage/agent/list', params);
    if (rsp.code !== 0) { toast(rsp.msg || '加载失败', true); return; }
    renderList(rsp.data || [], rsp.total || 0);
}

function renderList(rows, total) {
    agentPage.total = total;
    const tbody = document.getElementById('tbody');
    if (!rows.length) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:#95a5a6;">暂无数据</td></tr>';
    } else {
        tbody.innerHTML = rows.map(r => `
            <tr>
                <td><span class="mono">${escapeHtml(r.id)}</span></td>
                <td>${escapeHtml(r.name)}</td>
                <td>${escapeHtml(r.mission || '')}</td>
                <td>${r.maxSteps || ''}</td>
                <td>${statusTag(r.status)}</td>
                <td class="ops">
                    <button onclick='openEditor(${JSON.stringify(r.id)})'>编辑</button>
                    <button class="danger" onclick='delAgent(${JSON.stringify(r.id)})'>删除</button>
                </td>
            </tr>
        `).join('');
    }
    document.getElementById('total').textContent = total;
    const maxPage = Math.max(1, Math.ceil(total / agentPage.pageSize));
    document.getElementById('pageInfo').textContent = `${agentPage.pageNum} / ${maxPage}`;
}

function prevPage() {
    if (agentPage.pageNum <= 1) return;
    agentPage.pageNum--;
    loadList();
}
function nextPage() {
    const maxPage = Math.max(1, Math.ceil(agentPage.total / agentPage.pageSize));
    if (agentPage.pageNum >= maxPage) return;
    agentPage.pageNum++;
    loadList();
}

async function openEditor(id) {
    document.getElementById('editorTitle').textContent = id ? '编辑 Agent' : '新增 Agent';
    document.getElementById('f_id').value = '';
    document.getElementById('f_name').value = '';
    document.getElementById('f_mission').value = '';
    document.getElementById('f_pre_prompt').value = '';
    document.getElementById('f_model_name').value = '';
    document.getElementById('f_temperature').value = '';
    document.getElementById('f_max_steps').value = '6';
    document.getElementById('f_status').value = '1';
    document.getElementById('f_remark').value = '';
    if (id) {
        const rsp = await API.get('/manage/agent/get', { id });
        if (rsp.code !== 0) { toast(rsp.msg || '加载失败', true); return; }
        const a = rsp.data;
        document.getElementById('f_id').value = a.id;
        document.getElementById('f_name').value = a.name || '';
        document.getElementById('f_mission').value = a.mission || '';
        document.getElementById('f_pre_prompt').value = a.prePrompt || '';
        document.getElementById('f_model_name').value = a.modelName || '';
        document.getElementById('f_temperature').value = a.temperature == null ? '' : a.temperature;
        document.getElementById('f_max_steps').value = a.maxSteps || 6;
        document.getElementById('f_status').value = a.status == null ? '1' : String(a.status);
        document.getElementById('f_remark').value = a.remark || '';
    }
    showModal('editor');
}

function closeEditor() { hideModal('editor'); }

async function submitForm() {
    const id = document.getElementById('f_id').value;
    const body = {
        name: document.getElementById('f_name').value.trim(),
        mission: document.getElementById('f_mission').value.trim(),
        prePrompt: document.getElementById('f_pre_prompt').value,
        modelName: document.getElementById('f_model_name').value.trim() || null,
        temperature: document.getElementById('f_temperature').value === '' ? null : Number(document.getElementById('f_temperature').value),
        maxSteps: Number(document.getElementById('f_max_steps').value) || 6,
        status: Number(document.getElementById('f_status').value),
        remark: document.getElementById('f_remark').value.trim()
    };
    if (!body.name || !body.prePrompt) {
        toast('名称与前置 Prompt 必填', true);
        return;
    }
    let rsp;
    if (id) {
        body.id = id;
        rsp = await API.post('/manage/agent/update', body);
    } else {
        rsp = await API.post('/manage/agent/add', body);
    }
    if (tip(rsp, id ? '已更新' : '已新增')) {
        closeEditor();
        loadList();
    }
}

async function delAgent(id) {
    if (!confirm('确认删除该 Agent？此操作不可恢复，建议先确认无关联知识库。')) return;
    const rsp = await API.post('/manage/agent/delete', null, { id });
    if (tip(rsp, '已删除')) loadList();
}

// ===== 基础 API 封装 =====
const API = {
    async get(url, params) {
        const qs = params ? '?' + new URLSearchParams(params).toString() : '';
        const r = await fetch(url + qs, { method: 'GET' });
        return parseRsp(r);
    },
    async post(url, body, params) {
        const qs = params ? '?' + new URLSearchParams(params).toString() : '';
        const r = await fetch(url + qs, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: body == null ? undefined : JSON.stringify(body)
        });
        return parseRsp(r);
    }
};

async function parseRsp(resp) {
    if (!resp.ok) {
        return { code: resp.status, msg: 'HTTP ' + resp.status };
    }
    const j = await resp.json();
    return j;
}

// ===== Toast =====
function toast(msg, isError) {
    const el = document.createElement('div');
    el.className = 'toast' + (isError ? ' error' : '');
    el.textContent = msg;
    document.body.appendChild(el);
    setTimeout(() => { el.style.opacity = 0; }, 1800);
    setTimeout(() => { el.remove(); }, 2200);
}

function tip(rsp, okMsg) {
    if (rsp && rsp.code === 0) {
        toast(okMsg || '操作成功');
        return true;
    }
    toast('错误: ' + (rsp && rsp.msg ? rsp.msg : '未知错误'), true);
    return false;
}

// ===== Modal helpers =====
function showModal(id) {
    document.getElementById(id).classList.remove('hide');
}
function hideModal(id) {
    document.getElementById(id).classList.add('hide');
}

// ===== Utils =====
function escapeHtml(s) {
    if (s == null) return '';
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function formatTime(t) {
    if (!t) return '';
    try {
        const d = new Date(t);
        if (isNaN(d.getTime())) return t;
        const pad = n => String(n).padStart(2, '0');
        return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    } catch (e) {
        return t;
    }
}

function statusTag(status) {
    if (status === 1) return '<span class="tag tag-on">启用</span>';
    return '<span class="tag tag-off">禁用</span>';
}

// ===== Modal with full-text viewer =====
function showFullStep(btn) {
    const step = btn.parentNode;
    const type = step.querySelector('.type').textContent;
    const time = step.querySelector('.time').textContent;
    const contentEl = step.querySelector('.step-content');
    const texts = [];
    contentEl.querySelectorAll('pre').forEach(pre => texts.push(pre.textContent));
    const overlay = document.getElementById('modal-overlay');
    overlay.querySelector('.modal-meta').textContent = type + ' · ' + time;
    overlay.querySelector('.modal-body pre').textContent = texts.join('\n');
    overlay.classList.remove('hide');
}

function closeModal() {
    document.getElementById('modal-overlay').classList.add('hide');
}

document.addEventListener('DOMContentLoaded', function () {
    const overlay = document.getElementById('modal-overlay');
    if (overlay) {
        overlay.addEventListener('click', function (e) {
            if (e.target === this) closeModal();
        });
    }
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') {
            const overlay = document.getElementById('modal-overlay');
            if (overlay && !overlay.classList.contains('hide')) closeModal();
        }
    });
});

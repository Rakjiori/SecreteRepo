async function registerSW(){ if(!('serviceWorker'in navigator)) return null; return navigator.serviceWorker.register('/sw.js'); }
function b64ToU8(b64){const p='='.repeat((4-b64.length%4)%4);const a=(b64+p).replace(/-/g,'+').replace(/_/g,'/');const r=atob(a),u=new Uint8Array(r.length);for(let i=0;i<r.length;i++)u[i]=r.charCodeAt(i);return u;}
async function subscribePush(){
  if(!('Notification'in window)) { alert('브라우저가 알림을 지원하지 않습니다.'); return; }
  const perm = await Notification.requestPermission();
  if(perm!=='granted'){ alert('알림 허용이 필요합니다.'); return; }
  const reg = await registerSW();
  const pub = window.VAPID_PUBLIC || ''; // 추후 서버에서 주입
  if(!pub){ alert('VAPID 공개키가 설정되지 않았습니다.'); return; }
  const sub = await reg.pushManager.subscribe({ userVisibleOnly:true, applicationServerKey: b64ToU8(pub) });
  console.log('Subscribed:', sub);
}

async function scheduleNotificationsNow(){
  try{
    await fetch('/api/notifications/schedule-now', { credentials:'same-origin' });
  }catch(e){
    // ignore errors silently
  }
}

// 알림 벨 UI 상태
const notificationCenter = {
  items: [],
  seen: new Set(),
  bell: null,
  panel: null,
  list: null,
  badge: null,
  friendBadge: null
};

function renderNotificationCenter(){
  if(!notificationCenter.list) return;
  const { items, list, badge } = notificationCenter;
  list.innerHTML = '';
  if(!items.length){
    const empty = document.createElement('div');
    empty.className = 'notification-empty';
    empty.innerHTML = '알람이 없습니다.<br>모든 문제를 풀었어요.';
    list.appendChild(empty);
  } else {
    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
    items.forEach(n=>{
      if(n.type === 'groupInvite' && n.inviteId){
        const row = document.createElement('div');
        row.className = 'notification-item';
        row.innerHTML = `
          <div class="notification-title">${n.title || '그룹 초대'}</div>
          <div class="notification-body">${n.body || ''}</div>
          <div style="margin-top:6px; display:flex; gap:8px;">
            <button class="btn btn-outline" data-role="group-accept" data-id="${n.inviteId}" style="padding:6px 10px;">수락</button>
            <button class="btn btn-outline" data-role="group-reject" data-id="${n.inviteId}" style="padding:6px 10px;">거절</button>
          </div>
        `;
        list.appendChild(row);
        row.querySelectorAll('[data-role="group-accept"]').forEach(btn=>{
          btn.addEventListener('click', async (e)=>{
            e.preventDefault();
            try{
              await fetch(`/api/groups/invite/${n.inviteId}/accept`, {
                method:'POST',
                headers: csrfToken ? { [csrfHeader]: csrfToken } : {}
              });
              // 제거 후 다시 렌더
              notificationCenter.items = notificationCenter.items.filter(it => it !== n);
              renderNotificationCenter();
            }catch(err){ console.error(err); }
          });
        });
        row.querySelectorAll('[data-role="group-reject"]').forEach(btn=>{
          btn.addEventListener('click', async (e)=>{
            e.preventDefault();
            try{
              await fetch(`/api/groups/invite/${n.inviteId}/reject`, {
                method:'POST',
                headers: csrfToken ? { [csrfHeader]: csrfToken } : {}
              });
              notificationCenter.items = notificationCenter.items.filter(it => it !== n);
              renderNotificationCenter();
            }catch(err){ console.error(err); }
          });
        });
      } else {
        const link = document.createElement('a');
        link.className = 'notification-item';
        link.href = n.url || '/quiz/list';
        link.innerHTML = `
          <div class="notification-title">${n.title || '알림'}</div>
          <div class="notification-body">${n.body || ''}</div>
        `;
        list.appendChild(link);
      }
    });
  }
  if(badge){
    if(items.length){
      badge.style.display = 'inline-flex';
      badge.textContent = Math.min(items.length, 99);
    } else {
      badge.style.display = 'none';
    }
  }
}

function addNotificationsToCenter(items = []){
  if(!items || !items.length) return;
  let changed = false;
  items.forEach(n=>{
    const key = n.id ?? `${n.title}|${n.body}|${n.url}|${n.type||''}|${n.inviteId||''}`;
    if(notificationCenter.seen.has(key)) return;
    notificationCenter.seen.add(key);
    notificationCenter.items.unshift({
      title: n.title || '새 알림',
      body: n.body || '',
      url: n.url || '/quiz/list',
      type: n.type,
      inviteId: n.inviteId
    });
    changed = true;
  });
  if(notificationCenter.items.length > 20){
    notificationCenter.items = notificationCenter.items.slice(0, 20);
  }
  if(changed) renderNotificationCenter();
}

function setupNotificationBell(){
  notificationCenter.bell = document.getElementById('notificationBell');
  notificationCenter.panel = document.getElementById('notificationPanel');
  notificationCenter.list = document.getElementById('notificationList');
  notificationCenter.badge = document.getElementById('notificationBadge');
  notificationCenter.friendBadge = document.getElementById('friendBadge');
  if(!notificationCenter.bell || !notificationCenter.panel) return;

  notificationCenter.bell.addEventListener('click', (e)=>{
    e.preventDefault();
    // 다른 드롭다운 닫기
    const friendPanel = document.getElementById('friendPanel');
    const profilePanel = document.getElementById('profilePanel');
    if(friendPanel){ friendPanel.classList.remove('open'); friendPanel.style.display = 'none'; }
    if(profilePanel){ profilePanel.classList.remove('open'); profilePanel.style.display = 'none'; }

    const isOpen = notificationCenter.panel.classList.toggle('open');
    notificationCenter.panel.style.display = isOpen ? 'block' : 'none';
    if(isOpen){
      // 패널을 열 때 최신 알림(그룹 초대/함께풀기 포함)을 다시 불러온다.
      pollNotifications();
    }
  });
  document.addEventListener('click', (e)=>{
    if(!notificationCenter.panel) return;
    if(e.target.closest('.notif-wrapper')) return;
    notificationCenter.panel.classList.remove('open');
    notificationCenter.panel.style.display = 'none';
  });
  renderNotificationCenter();
}

function setupFriendDropdown(){
  const btn = document.getElementById('friendBell');
  const panel = document.getElementById('friendPanel');
  const listEl = document.getElementById('friendRequestList');
  const csrf = document.querySelector('meta[name="_csrf"]')?.content || document.getElementById('friendCsrfParam')?.value || '';
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || document.getElementById('friendCsrfHeader')?.value || 'X-CSRF-TOKEN';
  const addForm = document.getElementById('friendAddForm');
  const shareInputs = document.querySelectorAll('[data-role="friend-share"]');
  if(!btn || !panel) return;

  const renderRequests = async () => {
    if(!listEl) return;
    listEl.innerHTML = '';
    try{
      const res = await fetch('/api/friends/inbox', { credentials:'same-origin' });
      if(!res.ok) return;
      const data = await res.json();
      const recv = data.received || [];
      const sent = data.sent || [];
      const accepted = data.accepted || [];
      if(!recv.length && !sent.length && !accepted.length){
        listEl.innerHTML = '<div class="notification-empty">대기 중인 요청이 없습니다.</div>';
        const fb = notificationCenter.friendBadge;
        if(fb) fb.style.display = 'none';
        return;
      }
      const fb = notificationCenter.friendBadge;
      if(fb){
        const total = recv.length + accepted.length;
        if(total > 0){
          fb.style.display = 'inline-flex';
          fb.textContent = Math.min(total,99);
        } else {
          fb.style.display = 'none';
        }
      }
      recv.forEach(item=>{
        const row = document.createElement('div');
        row.className = 'notification-item';
        row.innerHTML = `
          <div class="notification-title">${item.from} 님이 친구 요청을 보냈습니다.</div>
          <div class="notification-body">${new Date(item.createdAt).toLocaleString()}</div>
          <div style="margin-top:6px; display:flex; gap:6px;">
            <button class="btn btn-outline" data-role="accept" data-id="${item.id}" style="padding:4px 8px;">수락</button>
            <button class="btn btn-outline" data-role="reject" data-id="${item.id}" style="padding:4px 8px;">거절</button>
          </div>
        `;
        listEl.appendChild(row);
      });
      if(sent.length){
        const header = document.createElement('div');
        header.className = 'notification-header';
        header.textContent = '보낸 요청(대기 중)';
        listEl.appendChild(header);
        sent.forEach(item=>{
          const row = document.createElement('div');
          row.className = 'notification-item';
          row.innerHTML = `
            <div class="notification-title">${item.to} 님에게 요청 보냄</div>
            <div class="notification-body">${new Date(item.createdAt).toLocaleString()}</div>
          `;
          listEl.appendChild(row);
        });
      }
      if(accepted.length){
        const header = document.createElement('div');
        header.className = 'notification-header';
        header.textContent = '수락됨';
        listEl.appendChild(header);
        accepted.forEach(item=>{
          const row = document.createElement('div');
          row.className = 'notification-item';
          row.innerHTML = `
            <div class="notification-title">${item.to} 님이 친구 요청을 수락했습니다.</div>
            <div class="notification-body">${new Date(item.createdAt).toLocaleString()}</div>
          `;
          listEl.appendChild(row);
        });
      }
      listEl.querySelectorAll('[data-role="accept"]').forEach(btn=>{
        btn.addEventListener('click', async (e)=>{
          e.stopPropagation();
          const id = btn.dataset.id;
          await fetch(`/api/friends/request/${id}/accept`, {
            method:'POST',
            headers:{ [csrfHeader]: csrf }
          });
          renderRequests();
        });
      });
      listEl.querySelectorAll('[data-role="reject"]').forEach(btn=>{
        btn.addEventListener('click', async (e)=>{
          e.stopPropagation();
          const id = btn.dataset.id;
          await fetch(`/api/friends/request/${id}/reject`, {
            method:'POST',
            headers:{ [csrfHeader]: csrf }
          });
          renderRequests();
        });
      });
    }catch(e){ console.error(e); }
  };

  if(addForm){
    addForm.addEventListener('submit', async (e)=>{
      e.preventDefault();
      const username = addForm.querySelector('input[name="username"]')?.value || '';
      if(!username) return;
      await fetch('/api/friends/request', {
        method:'POST',
        headers:{
          'Content-Type':'application/x-www-form-urlencoded',
          [csrfHeader]: csrf
        },
        body:new URLSearchParams({ username })
      });
      addForm.querySelector('input[name="username"]').value='';
      renderRequests();
    });
  }

  shareInputs.forEach(input => {
    const btn = input.querySelector('button');
    const friendId = input.dataset.friendId;
    btn?.addEventListener('click', async (e)=>{
      e.preventDefault();
      const qid = input.querySelector('input')?.value;
      if(!qid) return;
      await fetch('/api/friends/share', {
        method:'POST',
        headers:{
          'Content-Type':'application/x-www-form-urlencoded',
          [csrfHeader]: csrf
        },
        body: new URLSearchParams({ friendId, questionId: qid })
      });
      input.querySelector('input').value='';
      alert('문제를 공유했습니다.');
    });
  });

  btn.addEventListener('click', (e)=>{
    e.preventDefault();
    // 다른 드롭다운 닫기
    const notifPanel = document.getElementById('notificationPanel');
    const profilePanel = document.getElementById('profilePanel');
    if(notifPanel){ notifPanel.classList.remove('open'); notifPanel.style.display = 'none'; }
    if(profilePanel){ profilePanel.classList.remove('open'); profilePanel.style.display = 'none'; }

    const open = panel.classList.toggle('open');
    panel.style.display = open ? 'block' : 'none';
    if(open) renderRequests();
  });
  document.addEventListener('click', (e)=>{
    if(!panel) return;
    if(e.target.closest('.notif-wrapper')) return;
    panel.classList.remove('open');
    panel.style.display = 'none';
  });
}

function setupProfileDropdown(){
  const btn = document.getElementById('profileButton');
  const panel = document.getElementById('profilePanel');
  if(!btn || !panel) return;
  btn.addEventListener('click', (e)=>{
    e.preventDefault();
    // 다른 드롭다운 닫기
    const notifPanel = document.getElementById('notificationPanel');
    const friendPanel = document.getElementById('friendPanel');
    if(notifPanel){ notifPanel.classList.remove('open'); notifPanel.style.display = 'none'; }
    if(friendPanel){ friendPanel.classList.remove('open'); friendPanel.style.display = 'none'; }

    const open = panel.classList.toggle('open');
    panel.style.display = open ? 'block' : 'none';
  });
  document.addEventListener('click', (e)=>{
    if(!panel) return;
    if(e.target.closest('.profile-wrapper')) return;
    panel.classList.remove('open');
    panel.style.display = 'none';
  });
}

// 폴링 기반 웹 알림 (서버 push 없이도 동작)
async function pollNotifications(){
  const supportsNative = 'Notification' in window;
  try{
    // 그룹 초대 알림을 알림 벨에 표시
    try {
      const gRes = await fetch('/api/groups/invite/inbox', { credentials:'same-origin' });
      if(gRes.ok){
        const gData = await gRes.json();
        const recv = gData.received || [];
        const groupItems = recv.map(item => ({
          id: `group-invite-${item.id}`,
          title: `👥 그룹 초대: ${item.groupName || ''}`,
          body: `${item.from || '누군가'} 님이 초대했습니다. 코드: ${item.code || ''}`,
          url: '/groups',
          type: 'groupInvite',
          inviteId: item.id
        }));
        addNotificationsToCenter(groupItems);
      }
    } catch(err) {
      console.error(err);
    }
    // 함께 풀기 요청/알림을 알림 벨에 표시
    try {
      const fRes = await fetch('/api/friends/inbox', { credentials:'same-origin' });
      if(fRes.ok){
        const fData = await fRes.json();
        const shareReceived = fData.shareReceived || [];
        const shareAccepted = fData.shareAccepted || [];
        const shareItems = [
          ...shareReceived.map(item => ({
            id: `share-recv-${item.id}`,
            title: '🤝 함께 풀기 요청',
            body: `${item.from} 님이 함께 풀기를 요청했습니다.`,
            url: `/quiz/solve/${item.questionId}`
          })),
          ...shareAccepted.map(item => ({
            id: `share-acc-${item.id}`,
            title: '✅ 함께 풀기 수락',
            body: `${item.to} 님이 함께 풀기 요청을 수락했습니다.`,
            url: `/quiz/solve/${item.questionId}`
          }))
        ];
        addNotificationsToCenter(shareItems);
        if(supportsNative && Notification.permission === 'granted'){
          shareItems.forEach(n=>{
            const noti = new Notification(n.title, { body:n.body || '', data:{url:n.url||'/quiz/list'} });
            noti.onclick = () => { window.open(n.data.url, '_blank'); };
          });
        }
      }
    } catch(err) {
      console.error(err);
    }
  }catch(e){
    console.error('Notification poll error', e);
  }
}

// =======================
// Micro interactions
// =======================
function setupRevealAnimations(){
  const targets = document.querySelectorAll('[data-anim="fade-up"]');
  if(!targets.length) return;
  const io = new IntersectionObserver((entries)=>{
    entries.forEach(entry=>{
      if(entry.isIntersecting){
        const delay = entry.target.dataset.animDelay ? parseInt(entry.target.dataset.animDelay,10) : 0;
        setTimeout(()=> entry.target.classList.add('is-visible'), delay || 0);
        io.unobserve(entry.target);
      }
    });
  }, { threshold: 0.15 });
  targets.forEach(el=>io.observe(el));
}

function setupRipple(){
  document.addEventListener('click', (e)=>{
    const btn = e.target.closest('.btn, .btn-outline');
    if(!btn) return;
    const rect = btn.getBoundingClientRect();
    const ripple = document.createElement('span');
    ripple.style.position = 'absolute';
    ripple.style.borderRadius = '50%';
    ripple.style.transform = 'scale(0)';
    ripple.style.opacity = '0.3';
    ripple.style.background = 'currentColor';
    ripple.style.pointerEvents = 'none';
    ripple.style.width = ripple.style.height = Math.max(rect.width, rect.height) + 'px';
    ripple.style.left = (e.clientX - rect.left - rect.width) + 'px';
    ripple.style.top = (e.clientY - rect.top - rect.width) + 'px';
    ripple.style.transition = 'transform 0.4s ease, opacity 0.6s ease';
    ripple.className = 'btn-ripple';
    if(getComputedStyle(btn).position === 'static'){
      btn.style.position = 'relative';
    }
    btn.appendChild(ripple);
    requestAnimationFrame(()=>{
      ripple.style.transform = 'scale(1.3)';
      ripple.style.opacity = '0';
    });
    ripple.addEventListener('transitionend', ()=> ripple.remove());
  });
}

function decorateBlueButtons(){
  document.querySelectorAll('.btn:not(.btn-outline)').forEach(btn=>{
    if(!btn.classList.contains('square-spin')){
      btn.classList.add('square-spin');
    }
  });
}

document.addEventListener('DOMContentLoaded', ()=>{
  if('Notification' in window){
    // 한 번만 요청
    if(Notification.permission === 'default'){
      Notification.requestPermission().then(perm=>{
        if(perm === 'granted') scheduleNotificationsNow();
      });
    } else if(Notification.permission === 'granted'){
      scheduleNotificationsNow();
    }
    setInterval(pollNotifications, 60_000);
    pollNotifications();
  }
  setupNotificationBell();
  setupFriendDropdown();
  setupProfileDropdown();
  setupRevealAnimations();
  setupRipple();
  decorateBlueButtons();
});

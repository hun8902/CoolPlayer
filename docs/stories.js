(() => {
 const hero = document.querySelector('.hero-scroll');
 const section = document.querySelector('.wide-listening');
 const shot = document.querySelector('.wide-product');
 const reduced = matchMedia('(prefers-reduced-motion: reduce)');
 const clamp = value => Math.max(0, Math.min(1, value));
 let pending = 0;
 function update() {
   pending = 0;
   const height = innerHeight;
   if (hero) {
     const rect = hero.getBoundingClientRect();
     const top = innerWidth <= 700 ? 60 : 74;
     const distance = Math.max(1, rect.height - (height - top));
     const progress = reduced.matches ? 1 : clamp((top - rect.top) / distance);
     hero.style.setProperty('--unfold', progress.toFixed(4));
     hero.classList.toggle('is-folded', progress < .005);
     hero.classList.toggle('cover-facing', progress < .5);
   }
   if (section && shot) {
     const rect = shot.getBoundingClientRect();
     const middle = rect.top + rect.height / 2;
     const distance = Math.abs(middle - height * .5);
     const progress = clamp(1 - distance / (height * .75));
     shot.style.setProperty('--shot-scale', reduced.matches ? '1' : (.83 + .17 * progress).toFixed(4));
   }
 }
 function schedule() { if (!pending) pending = requestAnimationFrame(update); }
 addEventListener('scroll', schedule, {passive:true});
 addEventListener('resize', schedule, {passive:true});
 addEventListener('pageshow', schedule);
 reduced.addEventListener('change', schedule);
 addEventListener('pagehide', () => { cancelAnimationFrame(pending); pending = 0; });
 update();
})();

(() => {
 const root = document.querySelector('.comparison-view');
 if (!root) return;
 const buttons = [...root.querySelectorAll('[data-comparison]')];
 const views = {
   phone: [['assets/samsung-phone.jpg','Samsung Music'],['assets/player-lyrics.jpg','Cool Player']],
   wide: [['assets/samsung-wide.jpg','Samsung Music'],['assets/player-wide.jpg','Cool Player']]
 };
 const devices = [...root.querySelectorAll('.comparison-device')];
 const reduced = matchMedia('(prefers-reduced-motion: reduce)');
 devices.forEach((device,index) => {
   device.className = 'comparison-fold';
   device.innerHTML = `<div class="scroll-device"><div class="hinge-half hinge-left"><div class="hinge-face hinge-inside"><img src="${views.wide[index][0]}" alt="${views.wide[index][1]} 펼친 화면"></div><div class="hinge-face hinge-cover"><img src="${views.phone[index][0]}" alt="${views.phone[index][1]} 접힌 화면"></div></div><div class="hinge-half hinge-right"><div class="hinge-face hinge-inside"><img src="${views.wide[index][0]}" alt=""></div></div></div>`;
 });
 let selected = 'phone', progress = 0, animation = 0;
 function paint(value) {
   devices.forEach(device => {
     device.style.setProperty('--unfold',value.toFixed(4));
     device.classList.toggle('is-folded',value < .005);
     device.classList.toggle('cover-facing',value < .5);
   });
 }
 function select(mode) {
   if(mode === selected) return;
   selected = mode;
   root.classList.toggle('is-wide', mode === 'wide');
   buttons.forEach(item => item.setAttribute('aria-pressed',String(item.dataset.comparison === mode)));
   cancelAnimationFrame(animation);
   const start = progress, end = mode === 'wide' ? 1 : 0;
   if(reduced.matches) { progress=end;paint(progress);return; }
   let began;
   function frame(time) {
     if(began === undefined) began=time;
     const t=Math.min(1,(time-began)/850);
     const eased=t*t*(3-2*t);
     progress=start+(end-start)*eased;
     paint(progress);
     if(t<1) animation=requestAnimationFrame(frame);
   }
   animation=requestAnimationFrame(frame);
 }
 paint(0);
 let scrollMode = null, pending = false;
 buttons.forEach(button => button.addEventListener('click', () => select(button.dataset.comparison)));
 function updateScroll() {
   pending = false;
   // A stable anchor avoids feedback when the device height changes.
   const top = root.getBoundingClientRect().top;
   const next = top < innerHeight * .38 ? 'wide' : 'phone';
   if (next !== scrollMode) { scrollMode = next; select(next); }
 }
 addEventListener('scroll', () => {
   if (!pending) { pending = true; requestAnimationFrame(updateScroll); }
 }, {passive:true});
 addEventListener('resize', updateScroll, {passive:true});
 updateScroll();
})();

(() => {
 const root = document.querySelector('.journey-story');
 if (!root) return;
 const stage = root.querySelector('.journey-stage');
 const chapters = [...root.querySelectorAll('.journey-chapters article')];
 const images = [...root.querySelectorAll('.journey-phone img')];
 const counter = root.querySelector('.journey-position span');
 let selected = -1, pending = 0;
 function update() {
   pending = 0;
   const height = innerHeight;
   const bottom = stage.getBoundingClientRect().bottom;
   const target = innerWidth <= 700 ? bottom + (height - bottom) * .5 : height * .52;
   let nearest = 0, distance = Infinity;
   chapters.forEach((chapter, index) => {
     const rect = chapter.getBoundingClientRect();
     const next = Math.abs(rect.top + rect.height / 2 - target);
     if(next < distance) { distance = next; nearest = index; }
   });
   if (selected === nearest) return;
   selected = nearest;
   images.forEach((image, index) => {
     image.classList.toggle('current', index === selected);
     image.setAttribute('aria-hidden', String(index !== selected));
     chapters[index].classList.toggle('current', index === selected);
   });
   counter.textContent = String(selected + 1).padStart(2, '0');
 }
 function schedule() { if(!pending) pending = requestAnimationFrame(update); }
 addEventListener('scroll', schedule, {passive:true});
 addEventListener('resize', schedule, {passive:true});
 addEventListener('pageshow', schedule);
 addEventListener('pagehide', () => { cancelAnimationFrame(pending); pending = 0; });
 update();
})();

// Derive the closed frame height from the image area inside its metal and bezel.
(() => {
 const devices=[...document.querySelectorAll('.scroll-device')];
 const observer=new ResizeObserver(entries => {
   for(const entry of entries) {
     const device=entry.target;
     const width=entry.contentRect.width;
     const face=device.querySelector('.hinge-cover');
     const image=face && face.querySelector('img');
     if(!image || !width) continue;
     const style=getComputedStyle(face), bezel=getComputedStyle(image);
     const inset=parseFloat(style.paddingLeft)+parseFloat(bezel.borderLeftWidth);
     const ratio=image.naturalWidth/image.naturalHeight;
     if(!ratio) continue;
     const closedHeight=(width/2-inset*2)/ratio+inset*2;
     device.style.setProperty('--closed-ratio',String(width/closedHeight));
     const wrapper=device.closest('.comparison-fold');
     if(wrapper) wrapper.style.setProperty('--closed-ratio',String(width/closedHeight));
   }
 });
 devices.forEach(device=>observer.observe(device));
})();

(() => {
 const track=document.querySelector('.lyrics-rotation-track');
 if(!track) return;
 const reduced=matchMedia('(prefers-reduced-motion: reduce)');
 let pending=0;
 function update(){
   pending=0;
   const rect=track.getBoundingClientRect();
   const stage=track.querySelector('.lyrics-rotation-stage');
   const top=parseFloat(getComputedStyle(stage).top)||0;
   const distance=Math.max(1,rect.height-stage.getBoundingClientRect().height);
   const value=reduced.matches?0:Math.max(0,Math.min(1,(top-rect.top)/distance));
   track.style.setProperty('--turn',String(value*value*(3-2*value)));
 }
 function schedule(){if(!pending)pending=requestAnimationFrame(update);}
 addEventListener('scroll',schedule,{passive:true});
 addEventListener('resize',schedule,{passive:true});
 reduced.addEventListener('change',schedule);
 update();
})();

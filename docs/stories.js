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
 const images = [...root.querySelectorAll('.comparison-device img')];
 const views = {
   phone: [['assets/samsung-phone.jpg','삼성 Music의 접힌 화면'],['assets/player-lyrics.jpg','Cool Player의 접힌 화면, 가사를 가져온 후']],
   wide: [['assets/samsung-wide.jpg','삼성 Music의 펼친 화면'],['assets/player-wide.jpg','Cool Player의 펼친 화면, 앨범과 실시간 가사']]
 };
 // Prepare wide images without changing the selected view.
 views.wide.forEach(([src]) => { const image = new Image(); image.src = src; });
 const devices = [...root.querySelectorAll('.comparison-device')];
 const reduced = matchMedia('(prefers-reduced-motion: reduce)');
 let selected = 'phone';
 buttons.forEach(button => button.addEventListener('click', () => {
   const mode = button.dataset.comparison;
   if (mode === selected) return;
   const before = devices.map(device => device.getBoundingClientRect());
   devices.forEach(device => device.getAnimations().forEach(animation => animation.cancel()));
   images.forEach(image => image.getAnimations().forEach(animation => animation.cancel()));
   selected = mode;
   root.classList.toggle('is-wide', mode === 'wide');
   buttons.forEach(item => item.setAttribute('aria-pressed', String(item === button)));
   images.forEach((image, index) => {
     image.src = views[mode][index][0];
     image.alt = views[mode][index][1];
   });
   if (reduced.matches) return;
   devices.forEach((device, index) => {
     const after = device.getBoundingClientRect();
     const previous = before[index];
     // Animate the frame dimensions; never stretch or rotate the screenshot.
     device.animate([
       {width: `${previous.width}px`, height: `${previous.height}px`},
       {width: `${after.width}px`, height: `${after.height}px`}
     ], {duration:720, easing:'cubic-bezier(.4,0,.2,1)'});
     images[index].animate([{opacity:0},{opacity:0,offset:.25},{opacity:1}],
       {duration:720,easing:'ease-in-out'});
   });
 }));
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

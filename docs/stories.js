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

// Motion illustrates the product; screenshots remain the actual application UI.
(() => {
 const targets = document.querySelectorAll('.fold-visual, .wide-product');
 if (!('IntersectionObserver' in window)) {
   targets.forEach(target => target.classList.add('is-visible'));
   return;
 }
 const observer = new IntersectionObserver(entries => {
   entries.forEach(entry => {
     if (entry.isIntersecting) {
       entry.target.classList.add('is-visible');
       observer.unobserve(entry.target);
     }
   });
 }, {threshold: .2});
 targets.forEach(target => observer.observe(target));
})();

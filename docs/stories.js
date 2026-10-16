/* Scroll position selects the screen. No timers, tabs, or intercepted scrolling. */
(() => {
  const stories = [...document.querySelectorAll('[data-scroll-story]')].map(root => {
    const steps = [...root.querySelectorAll('.scroll-step')];
    const stage = document.createElement('div');
    stage.className = 'scroll-stage';
    const images = steps.map((step, i) => {
      const frame = step.querySelector('.step-image').cloneNode(true);
      frame.className += ' scroll-frame';
      frame.classList.toggle('current', i === 0);
      frame.setAttribute('aria-hidden', String(i !== 0));
      // All layers occupy the visible stage: load them before the next step is reached.
      frame.querySelector('img').loading = 'eager';
      stage.append(frame);
      return frame;
    });
    root.prepend(stage);
    root.classList.add('scroll-enhanced');
    return {root, steps, stage, images, selected: -1};
  });
  let pending = 0;
  function update() {
    pending = 0;
    const height = window.innerHeight;
    const mobile = matchMedia('(max-width: 760px)').matches;
    stories.forEach(story => {
      const bounds = story.root.getBoundingClientRect();
      if (bounds.bottom < 0 || bounds.top > height) return;
      const stageBottom = story.stage.getBoundingClientRect().bottom;
      const target = mobile ? Math.min(height * .83, stageBottom + (height - stageBottom) * .45) : height * .55;
      let selected = 0, distance = Infinity;
      story.steps.forEach((step, i) => {
        const rect = step.querySelector('.scroll-copy').getBoundingClientRect();
        const next = Math.abs(rect.top + rect.height / 2 - target);
        if (next < distance) { distance = next; selected = i; }
      });
      if (selected === story.selected) return;
      story.selected = selected;
      story.images.forEach((image, i) => {
        image.classList.toggle('current', i === selected);
        image.setAttribute('aria-hidden', String(i !== selected));
        story.steps[i].classList.toggle('current', i === selected);
      });
    });
  }
  function schedule() { if (!pending) pending = requestAnimationFrame(update); }
  window.addEventListener('scroll', schedule, {passive: true});
  window.addEventListener('resize', schedule, {passive: true});
  window.addEventListener('pageshow', schedule);
  window.addEventListener('pagehide', () => { cancelAnimationFrame(pending); pending = 0; });
  update();
})();

/* Screen stories stay local to the page; they never control music playback. */
(() => {
  const motion = matchMedia('(prefers-reduced-motion: reduce)');
  document.querySelectorAll('[data-story]').forEach(story => {
    const tabs = [...story.querySelectorAll('[role=tab]')];
    const panels = [...story.querySelectorAll('[role=tabpanel]')];
    const toggle = story.querySelector('.story-pause');
    let index = 0, timer, animationFrame, visible = false, hover = false, focused = false, paused = motion.matches;
    const stop = () => { clearTimeout(timer); cancelAnimationFrame(animationFrame); story.classList.remove('advancing'); };
    function schedule() {
      stop();
      toggle.hidden = motion.matches;
      toggle.textContent = paused ? '▶' : 'Ⅱ';
      toggle.setAttribute('aria-label', paused ? '화면 자동 전환 시작' : '화면 자동 전환 일시정지');
      if (paused || motion.matches || !visible || document.hidden || hover || focused) return;
      animationFrame = requestAnimationFrame(() => {
        if (paused || !visible || hover || focused || document.hidden || motion.matches) return;
        story.classList.add('advancing');
        timer = setTimeout(() => select((index + 1) % tabs.length), 7000);
      });
    }
    function select(next, manual = false) {
      index = next;
      if (manual) paused = true;
      tabs.forEach((tab, i) => {
        tab.setAttribute('aria-selected', String(i === index));
        tab.tabIndex = i === index ? 0 : -1;
        panels[i].classList.toggle('active', i === index);
        panels[i].setAttribute('aria-hidden', String(i !== index));
        panels[i].inert = i !== index;
      });
      schedule();
    }
    tabs.forEach((tab, i) => {
      tab.addEventListener('click', () => select(i, true));
      tab.addEventListener('keydown', event => {
        let next;
        if (event.key === 'ArrowRight') next = (i + 1) % tabs.length;
        if (event.key === 'ArrowLeft') next = (i - 1 + tabs.length) % tabs.length;
        if (event.key === 'Home') next = 0;
        if (event.key === 'End') next = tabs.length - 1;
        if (next !== undefined) { event.preventDefault(); select(next, true); tabs[next].focus(); }
      });
    });
    toggle.addEventListener('click', () => { paused = !paused; schedule(); });
    story.addEventListener('mouseenter', () => { hover = true; schedule(); });
    story.addEventListener('mouseleave', () => { hover = false; schedule(); });
    story.addEventListener('focusin', () => { focused = true; schedule(); });
    story.addEventListener('focusout', () => setTimeout(() => {
      focused = story.contains(document.activeElement); schedule();
    }, 0));
    document.addEventListener('visibilitychange', schedule);
    motion.addEventListener('change', () => { if (motion.matches) paused = true; schedule(); });
    window.addEventListener('pagehide', stop);
    window.addEventListener('pageshow', schedule);
    const observer = new IntersectionObserver(entries => {
      visible = entries[0].isIntersecting; schedule();
    }, {threshold: 0.25});
    story.classList.add('enhanced');
    select(0);
    observer.observe(story);
  });
})();

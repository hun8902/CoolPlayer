(() => {
 const audio = document.getElementById('hero-audio');
 const container = document.getElementById('hero-lyrics');
 const lines = [{"time": 0.0, "text": ""}, {"time": 0.62, "text": "Amazing grace"}, {"time": 3.42, "text": "How sweet the sound"}, {"time": 7.74, "text": "That saved a wretch like me"}, {"time": 18.98, "text": "I once was lost"}, {"time": 24.48, "text": "But now am found"}, {"time": 28.78, "text": "Was blind"}, {"time": 30.56, "text": "But now I see"}, {"time": 36.5, "text": ""}];
 const nodes = lines.map(line => {
   if (!line.text) return null;
   const node = document.createElement('button');
   node.type = 'button'; node.textContent = line.text;
   node.addEventListener('click', () => { audio.currentTime = line.time; audio.play().catch(() => {}); });
   container.append(node); return node;
 });
 let previous = -2;
 function update() {
   let active = -1;
   lines.forEach((line, i) => { if (line.time <= audio.currentTime) active = i; });
   if (active === previous) return;
   previous = active;
   nodes.forEach((node, i) => { if(node) { node.classList.toggle('active', i === active); node.setAttribute('aria-current', String(i === active)); } });
   const target = nodes[active];
   if (target) container.scrollTo({top: target.offsetTop - container.offsetTop - container.clientHeight / 2 + target.clientHeight / 2, behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth'});
 }
 audio.addEventListener('timeupdate', update);
 audio.addEventListener('seeked', update);
 update();
})();

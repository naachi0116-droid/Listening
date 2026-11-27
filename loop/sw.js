// オフラインでも開けるようにする（アプリ本体を端末に保存しておく）
// 本体はネットがあれば毎回新しいものを取りに行き（更新がすぐ届く）、つながらないときは保存しておいたものを使う
const CACHE = "loop-v1";
const FILES = ["./", "./index.html", "./manifest.json", "./icon-192.png", "./icon-512.png", "./mp4box.min.js"];
self.addEventListener("install", e=>{ e.waitUntil(caches.open(CACHE).then(c=>c.addAll(FILES)).then(()=>self.skipWaiting())); });
self.addEventListener("activate", e=>{ e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim())); });
self.addEventListener("fetch", e=>{
  const u = new URL(e.request.url);
  if(e.request.method !== "GET" || u.origin !== location.origin) return;   // OpenAIやBBCなど外への通信はそのまま
  e.respondWith((async ()=>{
    const c = await caches.open(CACHE);
    try{
      const ctl = new AbortController(); const tm = setTimeout(()=>ctl.abort(), 4000);
      const r = await fetch(e.request, {signal: ctl.signal}); clearTimeout(tm);
      if(r.ok){ const key = e.request.mode === "navigate" ? "./index.html" : e.request; c.put(key, r.clone()); }
      return r;
    }catch(err){
      return (await c.match(e.request, {ignoreSearch:true})) || (e.request.mode === "navigate" ? await c.match("./index.html") : undefined) || Response.error();
    }
  })());
});

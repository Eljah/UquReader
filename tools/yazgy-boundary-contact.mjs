import {chromium} from 'playwright';
import {readFileSync} from 'node:fs';
const root='.codex/editorial/yazgy/1948-10';
const browser=await chromium.launch({headless:true});
try {
  const page=await browser.newPage({viewport:{width:1520,height:750}});
  for(let first=38;first<=73;first+=6) {
    let html='<style>body{margin:0;display:grid;grid-template-columns:750px 750px;gap:10px;font:16px monospace}section{height:240px;overflow:hidden}img{width:750px;display:block}header{height:25px;background:white}</style>';
    for(let n=first;n<Math.min(first+6,74);n++) {
      const bytes=readFileSync(`${root}/images/${String(n).padStart(3,'0')}.jpg`);
      html+=`<section><header>Archive image / printed page ${n}</header><img src="data:image/jpeg;base64,${bytes.toString('base64')}"></section>`;
    }
    await page.setContent(html);
    await page.evaluate(()=>Promise.all([...document.images].map(i=>i.decode())));
    await page.screenshot({path:`.codex/editorial/yazgy/boundaries-${first}.png`,fullPage:true});
  }
}finally{await browser.close();}

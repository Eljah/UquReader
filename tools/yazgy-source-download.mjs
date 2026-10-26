import {mkdirSync, existsSync, readFileSync, writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
const root = '.codex/editorial/yazgy/1948-10';
mkdirSync(`${root}/images`,{recursive:true});
const manifestPath = `${root}/sources.json`;
const manifest = existsSync(manifestPath) ? JSON.parse(readFileSync(manifestPath,'utf8')) : {issue:'1948-10',pages:[]};
const start = Number(process.argv[2]), end = Number(process.argv[3] || start);
if (!Number.isInteger(start) || start < 1 || end < start || end > 140) throw new Error('Supply archive image range 1..140');
for (let page = start; page <= end; page++) {
  const name = `${String(page).padStart(3,'0')}.jpg`, file = `${root}/images/${name}`;
  const url = `https://kazanutlary.ru/images/uploads/j-archive/1948-10/${name}`;
  if (!existsSync(file)) {
    const response = await fetch(url,{signal:AbortSignal.timeout(30000)});
    if (!response.ok || !response.headers.get('content-type')?.startsWith('image/')) throw new Error(`${url}: ${response.status}`);
    const bytes = Buffer.from(await response.arrayBuffer());
    if (bytes[0] !== 255 || bytes[1] !== 216) throw new Error('Not JPEG');
    writeFileSync(file,bytes);
  }
  const entry = {archiveImage:page,file:name,url,sha256:createHash('sha256').update(readFileSync(file)).digest('hex')};
  manifest.pages = manifest.pages.filter(p => p.archiveImage !== page).concat(entry).sort((a,b)=>a.archiveImage-b.archiveImage);
  writeFileSync(manifestPath,JSON.stringify(manifest,null,2)+'\n');
  console.log(name,entry.sha256);
}

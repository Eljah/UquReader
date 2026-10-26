import {spawn} from 'node:child_process';
import {resolve} from 'node:path';

export async function connectEditor() {
  const child = spawn('ssh',['-T','-o','BatchMode=yes','-o','ConnectTimeout=10','-i',
    resolve('.codex/uqureader-editor-key'),'uqureader@192.168.0.120']);
  child.stderr.on('data',data=>process.stderr.write(data));
  const pending = new Map();
  let buffer = '', sequence = 0;
  child.stdout.setEncoding('utf8');
  child.stdout.on('data',chunk=>{
    buffer += chunk;
    while (buffer.includes('\n')) {
      const end = buffer.indexOf('\n'), line = buffer.slice(0,end);
      buffer = buffer.slice(end+1);
      if (!line.trim()) continue;
      const response = JSON.parse(line), request = pending.get(response.id);
      if (!request) continue;
      pending.delete(response.id); clearTimeout(request.timer);
      response.error ? request.reject(new Error(JSON.stringify(response.error))) : request.resolve(response.result);
    }
  });
  child.on('exit',code=>{
    for (const p of pending.values()) {clearTimeout(p.timer);p.reject(new Error(`SSH exited ${code}`));}
    pending.clear();
  });
  const rpc = (method,params)=>new Promise((resolve,reject)=>{
    const id = ++sequence;
    const timer = setTimeout(()=>{pending.delete(id);reject(new Error('MCP timeout'));child.kill();},180000);
    pending.set(id,{resolve,reject,timer});
    child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');
  });
  await rpc('initialize',{protocolVersion:'2024-11-05',capabilities:{},clientInfo:{name:'yazgy-editor',version:'1'}});
  child.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})+'\n');
  return {
    async call(name,args={}) {
      const result = await rpc('tools/call',{name,arguments:args});
      if(result.isError) throw new Error(JSON.stringify(result));
      const text = result.content?.find(c=>c.type==='text');
      return result.structuredContent ?? (text ? JSON.parse(text.text) : result);
    },
    close(){child.stdin.end();}
  };
}

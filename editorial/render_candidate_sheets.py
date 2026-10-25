"""Contact sheets of original PDF excerpts, not OCR or generated illustrations."""
import json
import math

import pymupdf

from store import Store

store = Store()
root = store.root / 'candidate-review-r64'
items = json.loads((root / 'candidates.json').read_text())
source_id = items[0]['sourceRefs'][0]['sourceId']
source = pymupdf.open(store.root / source_id)
font = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
size = 16
for start in range(0, len(items), size):
    batch = items[start:start+size]
    clips = []
    for item in batch:
        location = item['location']
        page = source[location['page']-1]
        boxes = location['boxes']
        rect = pymupdf.Rect(0, max(0, min(b[1] for b in boxes)-4), page.rect.width,
                            min(page.rect.height, max(b[3] for b in boxes)+4))
        clips.append((rect, max(40, rect.height * 960 / rect.width)))
    height = sum(h + 34 for _,h in clips) + 8
    sheet = pymupdf.open()
    page = sheet.new_page(width=1000, height=height)
    y = 4
    for item,(clip,h) in zip(batch,clips):
        label = f"#{item['id']} p{item['location']['page']}: {item['left']} + {item['right']} -> {item['joined']}"
        page.insert_text((10, y+16), label, fontname='review', fontfile=font, fontsize=14, color=(.6,0,0))
        page.show_pdf_page(pymupdf.Rect(20,y+24,980,y+24+h), source, item['location']['page']-1, clip=clip)
        y += h+34
    name = f'sheet-{start//size+1:02d}.png'
    page.get_pixmap(matrix=pymupdf.Matrix(1,1), alpha=False).save(root / name)
    print(name, flush=True)

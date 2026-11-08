# -*- coding: utf-8 -*-
"""读取竞赛附件3申报书(docx)与附件2选题参考(xlsx)，输出全部文本与表格结构"""
import sys
from docx import Document
from openpyxl import load_workbook

sys.stdout.reconfigure(encoding="utf-8")

BASE = r"D:\.ZenDox\obsidian\NomeLab\竞赛与活动\竞赛\学科\公安科技创新\文件\关于组织我院学生参加中国人民公安大学第三"

print("=" * 30, "附件3：申报书.docx", "=" * 30)
doc = Document(BASE + r"\附件3：申报书.docx")
for i, p in enumerate(doc.paragraphs):
    t = p.text.strip()
    if t:
        print(f"[P{i}] {t}")
for ti, tbl in enumerate(doc.tables):
    print(f"--- 表{ti}: {len(tbl.rows)}行 x {len(tbl.columns)}列 ---")
    seen = set()
    for ri, row in enumerate(tbl.rows):
        cells = []
        for c in row.cells:
            txt = c.text.strip().replace("\n", " / ")
            if txt and txt not in cells:
                cells.append(txt)
        line = " | ".join(cells)
        if line and line not in seen:
            seen.add(line)
            print(f"  R{ri}: {line[:300]}")

print("=" * 30, "附件2：选题参考.xlsx", "=" * 30)
wb = load_workbook(BASE + r"\附件2：选题参考.xlsx", read_only=True, data_only=True)
for ws in wb.worksheets:
    print(f"--- sheet: {ws.title} ({ws.max_row}x{ws.max_column}) ---")
    for row in ws.iter_rows(values_only=True):
        vals = [str(v).strip() for v in row if v is not None and str(v).strip()]
        if vals:
            print("  " + " | ".join(vals)[:250])

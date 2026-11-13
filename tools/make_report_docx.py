# -*- coding: utf-8 -*-
"""把 docs/研究报告-v1.2.md 转换为排版好的 Word 研究报告。

用法： python make_report_docx.py <in.md> <out.docx>
"""
import os
import re
import sys

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_LINE_SPACING
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

CONTENT_W = Cm(16.0)
IMG_W = Cm(13.6)
FONT_CN = "宋体"
FONT_CN_HEAD = "黑体"
FONT_CN_TITLE = "微软雅黑"
FONT_EN = "Times New Roman"
INK = RGBColor(0x1B, 0x22, 0x2E)
MUTED = RGBColor(0x5B, 0x67, 0x78)


def set_run_font(run, name_cn=FONT_CN, name_en=FONT_EN, size=None, bold=None,
                 color=None, italic=None):
    run.font.name = name_en
    rpr = run._element.get_or_add_rPr()
    rfonts = rpr.find(qn("w:rFonts"))
    if rfonts is None:
        rfonts = OxmlElement("w:rFonts")
        rpr.append(rfonts)
    rfonts.set(qn("w:eastAsia"), name_cn)
    rfonts.set(qn("w:ascii"), name_en)
    rfonts.set(qn("w:hAnsi"), name_en)
    if size is not None:
        run.font.size = Pt(size)
    if bold is not None:
        run.font.bold = bold
    if italic is not None:
        run.font.italic = italic
    if color is not None:
        run.font.color.rgb = color


def style_font(style, name_cn, name_en=FONT_EN, size=None, bold=None, color=None):
    style.font.name = name_en
    rpr = style.element.get_or_add_rPr()
    rfonts = rpr.find(qn("w:rFonts"))
    if rfonts is None:
        rfonts = OxmlElement("w:rFonts")
        rpr.append(rfonts)
    rfonts.set(qn("w:eastAsia"), name_cn)
    rfonts.set(qn("w:ascii"), name_en)
    rfonts.set(qn("w:hAnsi"), name_en)
    if size is not None:
        style.font.size = Pt(size)
    if bold is not None:
        style.font.bold = bold
    if color is not None:
        style.font.color.rgb = color


def shade(cell, hex_color):
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), hex_color)
    tc_pr.append(shd)


def set_cell_margins(table, top=60, bottom=60, left=90, right=90):
    tbl_pr = table._tbl.tblPr
    mar = OxmlElement("w:tblCellMar")
    for tag, val in (("top", top), ("left", left), ("bottom", bottom), ("right", right)):
        el = OxmlElement(f"w:{tag}")
        el.set(qn("w:w"), str(val))
        el.set(qn("w:type"), "dxa")
        mar.append(el)
    tbl_pr.append(mar)


def set_table_borders(table):
    tbl_pr = table._tbl.tblPr
    borders = OxmlElement("w:tblBorders")
    for edge in ("top", "left", "bottom", "right", "insideH", "insideV"):
        el = OxmlElement(f"w:{edge}")
        el.set(qn("w:val"), "single")
        el.set(qn("w:sz"), "4")
        el.set(qn("w:space"), "0")
        el.set(qn("w:color"), "B9C4D2")
        borders.append(el)
    tbl_pr.append(borders)


def repeat_header(row):
    tr_pr = row._tr.get_or_add_trPr()
    el = OxmlElement("w:tblHeader")
    el.set(qn("w:val"), "true")
    tr_pr.append(el)


def add_page_number_footer(section):
    footer = section.footer
    para = footer.paragraphs[0]
    para.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = para.add_run()
    set_run_font(run, size=9, color=MUTED)
    fld = OxmlElement("w:fldSimple")
    fld.set(qn("w:instr"), "PAGE")
    run._element.addnext(fld)
    inner = OxmlElement("w:r")
    text = OxmlElement("w:t")
    text.text = "1"
    inner.append(text)
    fld.append(inner)


def add_inline(paragraph, text, size=10.5, color=INK, base_bold=False):
    """处理 **加粗** 与 `等宽`。"""
    pattern = re.compile(r"(\*\*.+?\*\*|`.+?`)")
    for piece in pattern.split(text):
        if not piece:
            continue
        if piece.startswith("**") and piece.endswith("**"):
            run = paragraph.add_run(piece[2:-2])
            set_run_font(run, size=size, bold=True, color=color)
        elif piece.startswith("`") and piece.endswith("`"):
            run = paragraph.add_run(piece[1:-1])
            set_run_font(run, name_en="Consolas", size=size - 0.5, color=color)
        else:
            run = paragraph.add_run(piece)
            set_run_font(run, size=size, bold=base_bold, color=color)


def add_table(doc, rows):
    header, body = rows[0], rows[1:]
    table = doc.add_table(rows=len(rows), cols=len(header))
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    set_table_borders(table)
    set_cell_margins(table)

    weights = []
    for i in range(len(header)):
        col_vals = [header[i]] + [r[i] if i < len(r) else "" for r in body]
        weights.append(max(4, max(len(v) for v in col_vals)))
    total = sum(weights)
    widths = [CONTENT_W * (w / total) for w in weights]

    for c, cell in enumerate(table.rows[0].cells):
        shade(cell, "E8EEF5")
        cell.width = widths[c]
        para = cell.paragraphs[0]
        para.alignment = WD_ALIGN_PARAGRAPH.CENTER
        add_inline(para, header[c], size=9, base_bold=True)
    repeat_header(table.rows[0])

    for r_idx, row_vals in enumerate(body):
        for c, cell in enumerate(table.rows[r_idx + 1].cells):
            cell.width = widths[c]
            para = cell.paragraphs[0]
            para.paragraph_format.space_before = Pt(1)
            para.paragraph_format.space_after = Pt(1)
            para.paragraph_format.line_spacing = 1.15
            add_inline(para, row_vals[c] if c < len(row_vals) else "", size=9)
    doc.add_paragraph().paragraph_format.space_after = Pt(2)


def add_image(doc, path, caption):
    if not os.path.exists(path):
        print(f"[WARN] missing image: {path}")
        return
    from PIL import Image as _PILImage

    with _PILImage.open(path) as im:
        w_px, h_px = im.size
    max_h = Cm(19.5)
    width = IMG_W
    if width * h_px / w_px > max_h:
        width = max_h * w_px / h_px
    para = doc.add_paragraph()
    para.alignment = WD_ALIGN_PARAGRAPH.CENTER
    para.paragraph_format.space_before = Pt(6)
    para.paragraph_format.space_after = Pt(2)
    para.add_run().add_picture(path, width=width)
    if caption:
        cap = doc.add_paragraph()
        cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
        cap.paragraph_format.space_after = Pt(10)
        run = cap.add_run(caption)
        set_run_font(run, size=9, color=MUTED)


def build_cover(doc):
    for _ in range(3):
        doc.add_paragraph()
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run("中国人民公安大学第三届学生科技应用创新大赛")
    set_run_font(run, name_cn=FONT_CN_HEAD, size=13, color=MUTED)
    p.paragraph_format.space_after = Pt(30)

    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run("研 究 报 告")
    set_run_font(run, name_cn=FONT_CN_TITLE, size=34, bold=True, color=INK)
    p.paragraph_format.space_after = Pt(14)

    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run("「围猎」")
    set_run_font(run, name_cn=FONT_CN_TITLE, size=30, bold=True, color=INK)
    p.paragraph_format.space_after = Pt(8)

    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run("面向基层实战的普通手机基站覆盖边界协同实测系统")
    set_run_font(run, name_cn=FONT_CN_HEAD, size=15, color=INK)
    p.paragraph_format.space_after = Pt(48)

    meta = [
        ("参赛类别", "科技制作与技术应用类"),
        ("技术方向", "公安网络通信与移动应用关键技术"),
        ("作品名称", "围猎（备选：蜂巢猎影、基站围界）"),
        ("软件版本", "Android 客户端 v0.13.0 / 服务端 v0.13.0"),
        ("研制周期", "2026 年 11 月 6 日 — 2026 年 11 月 13 日"),
    ]
    for k, v in meta:
        p = doc.add_paragraph()
        p.paragraph_format.left_indent = Cm(3.4)
        p.paragraph_format.space_after = Pt(4)
        r1 = p.add_run(k + "　　")
        set_run_font(r1, size=11, color=MUTED)
        r2 = p.add_run(v)
        set_run_font(r2, size=11, color=INK)

    for _ in range(6):
        doc.add_paragraph()
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run("2026 年 11 月")
    set_run_font(run, name_cn=FONT_CN_HEAD, size=13, color=MUTED)
    doc.add_page_break()


def main():
    src, dst = sys.argv[1], sys.argv[2]
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(src)))
    with open(src, encoding="utf-8") as f:
        lines = f.read().splitlines()

    doc = Document()
    section = doc.sections[0]
    section.page_width, section.page_height = Cm(21.0), Cm(29.7)
    section.top_margin = section.bottom_margin = Cm(2.4)
    section.left_margin = section.right_margin = Cm(2.5)

    normal = doc.styles["Normal"]
    style_font(normal, FONT_CN, size=10.5, color=INK)
    normal.paragraph_format.line_spacing_rule = WD_LINE_SPACING.MULTIPLE
    normal.paragraph_format.line_spacing = 1.5
    normal.paragraph_format.space_after = Pt(4)

    for name, size in (("Heading 1", 16), ("Heading 2", 13.5), ("Heading 3", 12)):
        st = doc.styles[name]
        style_font(st, FONT_CN_HEAD, size=size, bold=True, color=INK)
        st.paragraph_format.space_before = Pt(14 if name == "Heading 1" else 10)
        st.paragraph_format.space_after = Pt(6)

    section.different_first_page_header_footer = True
    add_page_number_footer(section)
    build_cover(doc)

    # 跳过封面已承载的前置信息（标题 + 参赛信息），从「## 摘要」开始
    start = 0
    for idx, raw in enumerate(lines):
        if raw.startswith("## 摘要"):
            start = idx
            break
    else:
        start = 0

    i = start
    pending_caption = None
    table_buf = []

    def flush_table():
        nonlocal table_buf
        if table_buf:
            add_table(doc, table_buf)
            table_buf = []

    while i < len(lines):
        line = lines[i].rstrip()
        if not line.strip():
            flush_table()
            i += 1
            continue
        if line.strip() == "---":
            flush_table()
            pending_caption = None
            i += 1
            continue

        # 表格
        if line.lstrip().startswith("|"):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if all(re.fullmatch(r":?-{2,}:?", c) for c in cells if c):
                i += 1
                continue
            table_buf.append(cells)
            i += 1
            continue
        flush_table()

        # 标题
        if line.startswith("### "):
            doc.add_heading(line[4:].strip(), level=2)
            pending_caption = None
            i += 1
            continue
        if line.startswith("## "):
            doc.add_heading(line[3:].strip(), level=1)
            pending_caption = None
            i += 1
            continue
        if line.startswith("# "):
            doc.add_heading(line[2:].strip(), level=0)
            pending_caption = None
            i += 1
            continue

        # 图片
        m = re.match(r"!\[(.*?)\]\((.*?)\)", line.strip())
        if m:
            caption = pending_caption or m.group(1)
            add_image(doc, os.path.join(base_dir, m.group(2)), caption)
            pending_caption = None
            i += 1
            continue

        # 引用/图注
        if line.lstrip().startswith(">"):
            text = line.lstrip()[1:].strip()
            nxt = lines[i + 1].strip() if i + 1 < len(lines) else ""
            if nxt.startswith("!["):
                pending_caption = text
            else:
                para = doc.add_paragraph()
                para.paragraph_format.left_indent = Cm(0.6)
                add_inline(para, text, size=10, color=MUTED)
                pending_caption = None
            i += 1
            continue

        # 项目与编号列表
        list_match = re.match(r"^(\d+\.\s+|[-*]\s+)", line.strip())
        if list_match:
            body = line.strip()[len(list_match.group(1)):]
            para = doc.add_paragraph()
            para.paragraph_format.left_indent = Cm(0.75)
            para.paragraph_format.first_line_indent = Cm(-0.0)
            para.paragraph_format.space_after = Pt(3)
            marker = "•" if list_match.group(1).startswith(("-", "*")) else list_match.group(1).strip()
            prefix = marker if marker == "•" else marker
            run = para.add_run(("•　" if marker == "•" else prefix + "　"))
            set_run_font(run, size=10.5, color=INK)
            add_inline(para, body)
            i += 1
            continue

        # 普通段落
        para = doc.add_paragraph()
        para.paragraph_format.first_line_indent = Pt(21)
        add_inline(para, line.strip())
        i += 1

    flush_table()
    doc.core_properties.title = "「围猎」研究报告 v1.2"
    doc.core_properties.author = "围猎项目组"
    doc.save(dst)
    print("saved:", dst)


if __name__ == "__main__":
    main()

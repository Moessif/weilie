package com.gakj.weilie.core.fitting

/**
 * Marching Squares 等值线提取。
 * 输入规则网格与阈值，输出按端点拼接后的闭合环/开折线集合（米制局部坐标）。
 * 16 case 标准实现；case 5/10 鞍点用中心均值消歧。
 */
object MarchingSquares {

    fun isolines(grid: IdwGrid, threshold: Double): List<List<Pt>> {
        val segs = ArrayList<Pair<Pt, Pt>>()
        val vals = grid.values
        fun v(c: Int, r: Int): Double = if (c < 0 || r < 0 || c >= grid.cols || r >= grid.rows) Double.NaN else vals[r * grid.cols + c]
        fun interp(va: Double, vb: Double, a: Double, b: Double): Double {
            val d = vb - va
            return if (d == 0.0) 0.5 else ((threshold - va) / d).coerceIn(0.0, 1.0)
        }
        for (r in 0 until grid.rows - 1) {
            for (c in 0 until grid.cols - 1) {
                val tl = v(c, r + 1); val tr = v(c + 1, r + 1)
                val bl = v(c, r); val br = v(c + 1, r)
                if (tl.isNaN() || tr.isNaN() || bl.isNaN() || br.isNaN()) continue
                val x0 = grid.minX + c * grid.cellW
                val y0 = grid.minY + r * grid.cellH
                val x1 = x0 + grid.cellW
                val y1 = y0 + grid.cellH
                var idx = 0
                if (bl >= threshold) idx = idx or 1
                if (br >= threshold) idx = idx or 2
                if (tr >= threshold) idx = idx or 4
                if (tl >= threshold) idx = idx or 8
                if (idx == 0 || idx == 15) continue
                val top = Pt(x0 + interp(tl, tr, x0, x1) * grid.cellW, y1)
                val bottom = Pt(x0 + interp(bl, br, x0, x1) * grid.cellW, y0)
                val left = Pt(x0, y0 + interp(bl, tl, y0, y1) * grid.cellH)
                val right = Pt(x1, y0 + interp(br, tr, y0, y1) * grid.cellH)
                when (idx) {
                    1, 14 -> segs.add(left to bottom)
                    2, 13 -> segs.add(bottom to right)
                    3, 12 -> segs.add(left to right)
                    4, 11 -> segs.add(top to right)
                    6, 9 -> segs.add(top to bottom)
                    7, 8 -> segs.add(left to top)
                    5 -> { // 鞍点：中心值决定连通方式
                        val center = (tl + tr + bl + br) / 4
                        if (center >= threshold) { segs.add(left to top); segs.add(bottom to right) }
                        else { segs.add(left to bottom); segs.add(top to right) }
                    }
                    10 -> {
                        val center = (tl + tr + bl + br) / 4
                        if (center >= threshold) { segs.add(left to bottom); segs.add(top to right) }
                        else { segs.add(left to top); segs.add(bottom to right) }
                    }
                }
            }
        }
        return chain(segs)
    }

    /** 把无向线段按端点拼成折线；闭合环首尾相接 */
    private fun chain(segs: List<Pair<Pt, Pt>>): List<List<Pt>> {
        if (segs.isEmpty()) return emptyList()
        val key = { p: Pt -> "%.3f,%.3f".format(p.x, p.y) }
        val adjacency = HashMap<String, MutableList<Int>>()
        segs.forEachIndexed { i, (a, b) ->
            adjacency.getOrPut(key(a)) { mutableListOf() }.add(i)
            adjacency.getOrPut(key(b)) { mutableListOf() }.add(i)
        }
        val used = BooleanArray(segs.size)
        val results = ArrayList<List<Pt>>()
        for (start in segs.indices) {
            if (used[start]) continue
            used[start] = true
            val line = ArrayList<Pt>()
            line.add(segs[start].first); line.add(segs[start].second)
            // 向两端延伸
            var extended = true
            while (extended) {
                extended = false
                val tailKey = key(line.last())
                val cand = adjacency[tailKey]?.firstOrNull { !used[it] }
                if (cand != null) {
                    used[cand] = true
                    val (a, b) = segs[cand]
                    line.add(if (key(a) == tailKey) b else a)
                    extended = true
                    continue
                }
                val headKey = key(line.first())
                val cand2 = adjacency[headKey]?.firstOrNull { !used[it] }
                if (cand2 != null) {
                    used[cand2] = true
                    val (a, b) = segs[cand2]
                    line.add(0, if (key(a) == headKey) b else a)
                    extended = true
                }
            }
            results.add(line)
        }
        return results.sortedByDescending { line ->
            var len = 0.0
            for (i in 0 until line.size - 1) {
                len += (line[i + 1].x - line[i].x) * (line[i + 1].x - line[i].x) +
                    (line[i + 1].y - line[i].y) * (line[i + 1].y - line[i].y)
            }
            len
        }
    }
}

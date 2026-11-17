// CSV 解析与文件下载工具

export interface ParsedRow {
  row: number
  username: string
  displayName: string
  phone: string
  error: string
}

export interface ParseResult {
  rows: ParsedRow[]
  valid: ParsedRow[]
  invalid: ParsedRow[]
  headerDetected: boolean
}

function splitCsvLine(line: string): string[] {
  const out: string[] = []
  let cur = ''
  let quoted = false
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]
    if (quoted) {
      if (ch === '"') {
        if (line[i + 1] === '"') {
          cur += '"'
          i++
        } else {
          quoted = false
        }
      } else {
        cur += ch
      }
    } else if (ch === '"') {
      quoted = true
    } else if (ch === ',' || ch === '\t') {
      out.push(cur.trim())
      cur = ''
    } else {
      cur += ch
    }
  }
  out.push(cur.trim())
  return out
}

function isHeader(cols: string[]): boolean {
  const first = (cols[0] || '').toLowerCase()
  return ['username', '用户名', '登录名', '账号'].includes(first)
}

/**
 * 解析粘贴的 CSV（也兼容制表符分隔、表头可选）。
 * 列顺序：username,displayName,phone
 */
export function parseUserCsv(text: string): ParseResult {
  const rawLines = text
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
    .split('\n')
    .filter((line) => line.trim().length > 0)

  const rows: ParsedRow[] = []
  let headerDetected = false
  const seen = new Set<string>()

  rawLines.forEach((line, index) => {
    const cols = splitCsvLine(line)
    if (index === 0 && isHeader(cols)) {
      headerDetected = true
      return
    }
    const username = (cols[0] || '').trim()
    const displayName = (cols[1] || '').trim()
    const phone = (cols[2] || '').trim()
    const rowNo = index + 1

    let error = ''
    if (!username) {
      error = '用户名为空'
    } else if (!/^[a-zA-Z0-9_.-]{3,32}$/.test(username)) {
      error = '用户名需 3–32 位字母、数字或 _ . -'
    } else if (seen.has(username.toLowerCase())) {
      error = '用户名与前面的行重复'
    } else if (displayName.length > 20) {
      error = '显示名过长（≤20 字）'
    } else if (phone && !/^\d{6,15}$/.test(phone)) {
      error = '手机号格式不正确'
    }
    if (!error) seen.add(username.toLowerCase())

    rows.push({
      row: rowNo,
      username,
      displayName: displayName || username,
      phone,
      error,
    })
  })

  return {
    rows,
    valid: rows.filter((r) => !r.error),
    invalid: rows.filter((r) => r.error),
    headerDetected,
  }
}

export function downloadText(filename: string, text: string, mime = 'text/plain;charset=utf-8'): void {
  const bom = mime.includes('csv') ? '\uFEFF' : ''
  const blob = new Blob([bom + text], { type: mime })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export function toCsv(headers: string[], rows: (string | number | null | undefined)[][]): string {
  const escape = (v: string | number | null | undefined) => {
    const s = v === null || v === undefined ? '' : String(v)
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
  }
  return [headers.map(escape).join(','), ...rows.map((r) => r.map(escape).join(','))].join('\n')
}

export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    /* 回退 execCommand */
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}

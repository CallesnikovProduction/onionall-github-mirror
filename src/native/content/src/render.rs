// Authors: Стефан Колесников, GPT-6.1 Sol.
#![forbid(unsafe_code)]

pub const MAX_FRAME: usize = 2 * 1024 * 1024;
const MAX_UNITS: usize = 131_072;
const MAX_DEPTH: usize = 128;
const MAX_NODES: usize = 100_000;
const PUNCTUATION: &str = "\\`*_{}[]()#+-.!|>";

#[derive(Debug, PartialEq, Eq)]
pub enum Error {
    Malformed,
    Version,
    FrameLimit,
}

#[derive(Debug, PartialEq, Eq)]
struct Limit;

#[derive(Clone, Copy)]
struct Node<'a> {
    kind: u8,
    value: &'a str,
    end: usize,
}

struct Reader<'a> {
    bytes: &'a [u8],
    nodes: Vec<Node<'a>>,
}

impl<'a> Reader<'a> {
    fn take(&mut self, size: usize) -> Result<&'a [u8], Error> {
        let (head, tail) = self.bytes.split_at_checked(size).ok_or(Error::Malformed)?;
        self.bytes = tail;
        Ok(head)
    }

    fn byte(&mut self) -> Result<u8, Error> {
        let [value] = self.take(1)?.try_into().map_err(|_| Error::Malformed)?;
        Ok(value)
    }

    fn len(&mut self) -> Result<usize, Error> {
        let bytes = self.take(4)?.try_into().map_err(|_| Error::Malformed)?;
        Ok(u32::from_le_bytes(bytes) as usize)
    }

    fn tree(&mut self, kind: u8, depth: usize) -> Result<(), Error> {
        if depth > MAX_DEPTH + 1 || self.nodes.len() >= MAX_NODES {
            return Err(Error::Malformed);
        }
        // Payload-bearing tokens: text, image alternative, code, resolved link,
        // and an ordered item with its already observed ordinal.
        let value = match kind {
            b't' | b'j' | b'c' | b'p' | b'a' | b'o' => {
                let size = self.len()?;
                std::str::from_utf8(self.take(size)?).map_err(|_| Error::Malformed)?
            }
            _ => "",
        };
        // These are syntax-bearing fields, unlike ordinary text. Kotlin still
        // owns URL policy and assigns ordinals from the verified DOM.
        if kind == b'a'
            && (value.len() > 2048
                || !(value.starts_with("http://") || value.starts_with("https://"))
                || value
                    .bytes()
                    .any(|c| !c.is_ascii_graphic() || matches!(c, b'<' | b'>' | b'\\')))
            || kind == b'o' && (value.len() > 20 || value.parse::<i64>().is_err())
        {
            return Err(Error::Malformed);
        }
        let index = self.nodes.len();
        self.nodes.push(Node {
            kind,
            value,
            end: index + 1,
        });
        match kind {
            b't' | b'j' | b'c' | b'p' | b'n' | b'r' | b'x' | b'f' => {}
            b'B'
            | b'N'
            | b'a'
            | b'q'
            | b'1'..=b'6'
            | b'l'
            | b'o'
            | b's'
            | b'e'
            | b'T'
            | b'R'
            | b'H'
            | b'D'
            | b'C' => loop {
                let child = self.byte()?;
                if child == 0 {
                    break;
                }
                self.tree(child, depth + 1)?;
            },
            _ => return Err(Error::Malformed),
        }
        let end = self.nodes.len();
        self.nodes.get_mut(index).ok_or(Error::Malformed)?.end = end;
        Ok(())
    }
}

pub fn document(input: &[u8]) -> Result<Vec<u8>, Error> {
    if input.len() > MAX_FRAME {
        return Err(Error::Malformed);
    }
    let mut reader = Reader {
        bytes: input,
        nodes: Vec::new(),
    };
    match reader.take(4)? {
        b"OCT1" => return text_batch(reader),
        b"OCM1" => {}
        _ => return Err(Error::Version),
    }
    let count = reader.byte()?;
    if !(1..=101).contains(&count) {
        return Err(Error::Malformed);
    }
    let limit = reader.len()?;
    if limit > MAX_UNITS {
        return Err(Error::Malformed);
    }
    let mut response = Vec::from(&b"OCM1"[..]);
    response.push(count);
    for _ in 0..count {
        reader.nodes.clear();
        let kind = reader.byte()?;
        reader.tree(kind, 0)?;
        let mut buffer = Buffer::trimmed(limit, true);
        let mut limited = reader.nodes.iter().any(|node| node.kind == b'x');
        let rendered = if reader.nodes.iter().any(|node| node.kind == b'f') {
            Err(Limit)
        } else {
            render(&reader.nodes, 0, &mut buffer)
        };
        let trimmed = buffer.text.trim_matches(white);
        let fallback;
        let text = if rendered.is_err() || units(trimmed) > limit {
            limited = true;
            fallback = plain_fallback(&reader.nodes, limit);
            fallback.as_str()
        } else {
            trimmed
        };
        if response.len().saturating_add(5).saturating_add(text.len()) > MAX_FRAME {
            return Err(Error::FrameLimit);
        }
        response.push(u8::from(limited));
        response.extend_from_slice(&(text.len() as u32).to_le_bytes());
        response.extend_from_slice(text.as_bytes());
    }
    if !reader.bytes.is_empty() {
        return Err(Error::Malformed);
    }
    Ok(response)
}

fn text_batch(mut reader: Reader<'_>) -> Result<Vec<u8>, Error> {
    let count = reader.len()?;
    if !(1..=10_201).contains(&count) {
        return Err(Error::Malformed);
    }
    let mut response = Vec::from(&b"OCT1"[..]);
    response.extend_from_slice(&(count as u32).to_le_bytes());
    for _ in 0..count {
        let size = reader.len()?;
        let value = std::str::from_utf8(reader.take(size)?).map_err(|_| Error::Malformed)?;
        if units(value) > 65_536 {
            return Err(Error::Malformed);
        }
        // CRLF is one line break. Keep at most two successive line breaks
        // after right-trimming each line, exactly as the JVM projection did.
        let mut normalized = String::with_capacity(value.len());
        let mut lines = value
            .trim_matches(white)
            .split_inclusive(['\r', '\n'])
            .peekable();
        let mut breaks = 0;
        while let Some(line) = lines.next() {
            let text = line.trim_end_matches(white);
            if !text.is_empty() {
                normalized.push_str(text);
                breaks = 0;
            }
            if line.ends_with('\r') && lines.peek() == Some(&"\n") {
                lines.next();
            }
            if lines.peek().is_some() && breaks < 2 {
                normalized.push('\n');
                breaks += 1;
            }
        }
        if response
            .len()
            .saturating_add(4)
            .saturating_add(normalized.len())
            > MAX_FRAME
        {
            return Err(Error::Malformed);
        }
        response.extend_from_slice(&(normalized.len() as u32).to_le_bytes());
        response.extend_from_slice(normalized.as_bytes());
    }
    if !reader.bytes.is_empty() {
        return Err(Error::Malformed);
    }
    Ok(response)
}

fn units(value: &str) -> usize {
    if value.is_ascii() {
        value.len()
    } else {
        value.encode_utf16().count()
    }
}

fn white(c: char) -> bool {
    (c.is_whitespace() && c != '\u{85}') || matches!(c, '\u{1c}'..='\u{1f}')
}

struct Buffer {
    text: String,
    remaining: usize,
    trim_edges: bool,
    cut_tail: bool,
    allow_block_code: bool,
}

impl Buffer {
    fn new(remaining: usize) -> Self {
        Self {
            text: String::new(),
            remaining,
            trim_edges: false,
            cut_tail: false,
            allow_block_code: false,
        }
    }

    fn trimmed(remaining: usize, allow_block_code: bool) -> Self {
        Self {
            trim_edges: true,
            allow_block_code,
            ..Self::new(remaining)
        }
    }

    fn push(&mut self, mut value: &str) -> Result<(), Limit> {
        if self.trim_edges && self.text.is_empty() {
            value = value.trim_start_matches(white);
        }
        if self.cut_tail {
            return if value.chars().all(white) {
                Ok(())
            } else {
                Err(Limit)
            };
        }
        let mut length = units(value);
        if length > self.remaining && self.trim_edges {
            // A final whitespace run is trimmed, so it need not fit. Any later
            // non-whitespace would make that run part of the output and fail.
            value = value.trim_end_matches(white);
            length = units(value);
            self.cut_tail = true;
        }
        self.remaining = self.remaining.checked_sub(length).ok_or(Limit)?;
        self.text.push_str(value);
        Ok(())
    }

    fn character(&mut self, value: char) -> Result<(), Limit> {
        let size = value.len_utf16();
        if self.cut_tail || (self.trim_edges && (self.text.is_empty() || size > self.remaining)) {
            return self.push(value.encode_utf8(&mut [0; 4]));
        }
        self.remaining = self.remaining.checked_sub(size).ok_or(Limit)?;
        self.text.push(value);
        Ok(())
    }

    fn escape(&mut self, value: &str) -> Result<(), Limit> {
        let mut copied = 0;
        for (offset, c) in value.char_indices() {
            let replacement = match c {
                '&' => "&amp;",
                '<' => "&lt;",
                '>' => "&gt;",
                '\u{0}'..='\u{1f}' | '\u{7f}'..='\u{9f}' if !matches!(c, '\n' | '\r' | '\t') => "�",
                _ if PUNCTUATION.contains(c) => "\\",
                _ => continue,
            };
            self.push(value.get(copied..offset).ok_or(Limit)?)?;
            self.push(replacement)?;
            if replacement == "\\" {
                self.character(c)?;
            }
            copied = offset + c.len_utf8();
        }
        self.push(value.get(copied..).ok_or(Limit)?)
    }

    fn marker(&mut self, count: usize) -> Result<(), Limit> {
        if self.cut_tail {
            return Err(Limit);
        }
        self.remaining = self.remaining.checked_sub(count).ok_or(Limit)?;
        self.text.extend(std::iter::repeat_n('`', count));
        Ok(())
    }
}

fn plain_fallback(nodes: &[Node<'_>], limit: usize) -> String {
    let mut out = Buffer::new(limit);
    for node in nodes {
        let value = match node.kind {
            b't' | b'c' | b'p' => node.value,
            b'n' => "\n",
            _ => continue,
        };
        // Stop before an escape or Unicode scalar which cannot fit atomically.
        for ch in value.chars() {
            let mut encoded = [0; 4];
            let checkpoint = out.text.len();
            if out.escape(ch.encode_utf8(&mut encoded)).is_err() {
                out.text.truncate(checkpoint);
                return out.text;
            }
        }
    }
    out.text
}

fn children<'a>(nodes: &'a [Node<'a>], index: usize) -> impl Iterator<Item = usize> + 'a {
    let end = nodes.get(index).map_or(index, |node| node.end);
    let mut next = index + 1;
    std::iter::from_fn(move || {
        if next >= end {
            return None;
        }
        let child = next;
        next = nodes.get(child)?.end;
        Some(child)
    })
}

fn body(nodes: &[Node<'_>], index: usize, out: &mut Buffer) -> Result<(), Limit> {
    for child in children(nodes, index) {
        render(nodes, child, out)?;
    }
    Ok(())
}

fn fragment(nodes: &[Node<'_>], index: usize, out: &Buffer) -> Result<String, Limit> {
    let mut inner = Buffer::trimmed(out.remaining, false);
    body(nodes, index, &mut inner)?;
    Ok(inner.text)
}

fn render(nodes: &[Node<'_>], index: usize, out: &mut Buffer) -> Result<(), Limit> {
    let node = nodes.get(index).ok_or(Limit)?;
    let value = node.value;
    match node.kind {
        b't' | b'j' => out.escape(value),
        b'x' => Ok(()),
        b'n' => out.push("\n"),
        b'r' => out.push("\n\n---\n\n"),
        b'T' => table(nodes, index, out),
        b'p' if !out.allow_block_code && inline_markup(value) => {
            out.push("\n\n")?;
            out.escape(value)?;
            out.push("\n\n")
        }
        b'c' if inline_markup(value) => out.escape(value),
        b'p' | b'c' => {
            let fence = value
                .as_bytes()
                .split(|byte| *byte != b'`')
                .map(<[u8]>::len)
                .max()
                .unwrap_or(0)
                + 1;
            if node.kind == b'p' {
                let fence = fence.max(3);
                out.push("\n\n")?;
                out.marker(fence)?;
                out.push("\n")?;
                out.push(value)?;
                if !value.ends_with('\n') {
                    out.push("\n")?;
                }
                out.marker(fence)?;
                out.push("\n\n")
            } else {
                out.marker(fence)?;
                out.push(" ")?;
                let single_line = if value.contains('\r') {
                    std::borrow::Cow::Owned(value.replace("\r\n", "\n"))
                } else {
                    std::borrow::Cow::Borrowed(value)
                };
                for (i, line) in single_line.split(['\r', '\n']).enumerate() {
                    if i > 0 {
                        out.push(" ")?;
                    }
                    out.push(line)?;
                }
                out.push(" ")?;
                out.marker(fence)
            }
        }
        b'a' => {
            out.push("[")?;
            inline_body(nodes, index, out)?;
            out.push("](<")?;
            out.push(value)?;
            out.push(">)")
        }
        b's' | b'e' | b'B' | b'R' | b'H' | b'D' | b'C' => {
            let marker = match node.kind {
                b's' => "**",
                b'e' => "*",
                _ => "\n\n",
            };
            out.push(marker)?;
            if matches!(node.kind, b's' | b'e') {
                inline_body(nodes, index, out)?;
            } else {
                body(nodes, index, out)?;
            }
            out.push(marker)
        }
        b'q' | b'l' | b'o' | b'1'..=b'6' => {
            let content = fragment(nodes, index, out)?;
            let content = content.trim_matches(white);
            match node.kind {
                b'q' => {
                    out.push("\n\n> ")?;
                    let mut parts = content.split_inclusive(['\r', '\n']).peekable();
                    while let Some(part) = parts.next() {
                        out.push(part.trim_end_matches(['\r', '\n']))?;
                        if part.ends_with('\r') && parts.peek() == Some(&"\n") {
                            parts.next();
                        }
                        if parts.peek().is_some() {
                            out.push("\n> ")?;
                        }
                    }
                    out.push("\n\n")
                }
                b'l' | b'o' => {
                    out.push("\n")?;
                    if node.kind == b'o' {
                        out.push(value)?;
                        out.push(". ")?;
                    } else {
                        out.push("- ")?;
                    }
                    for (i, line) in content.split('\n').enumerate() {
                        if i > 0 {
                            out.push("\n  ")?;
                        }
                        out.push(line)?;
                    }
                    out.push("\n")
                }
                level => {
                    out.push("\n\n")?;
                    for _ in b'0'..level {
                        out.push("#")?;
                    }
                    out.push(" ")?;
                    out.push(content)?;
                    out.push("\n\n")
                }
            }
        }
        _ => body(nodes, index, out),
    }
}

fn inline_markup(value: &str) -> bool {
    value.contains(['[', ']'])
        || value
            .as_bytes()
            .windows(2)
            .any(|pair| matches!(pair, [b'<', next] if !next.is_ascii_whitespace()))
}

fn inline_body(nodes: &[Node<'_>], index: usize, out: &mut Buffer) -> Result<(), Limit> {
    let allow = std::mem::replace(&mut out.allow_block_code, false);
    let result = body(nodes, index, out);
    out.allow_block_code = allow;
    result
}

fn table(nodes: &[Node<'_>], index: usize, out: &mut Buffer) -> Result<(), Limit> {
    let mut rows = Vec::new();
    let end = nodes.get(index).ok_or(Limit)?.end;
    let mut next = index + 1;
    let mut width = 0;
    while next < end {
        let node = nodes.get(next).ok_or(Limit)?;
        match node.kind {
            b'T' => next = node.end,
            b'R' => {
                let cells: Vec<_> = children(nodes, next)
                    .filter(|&child| {
                        nodes
                            .get(child)
                            .is_some_and(|n| matches!(n.kind, b'H' | b'D'))
                    })
                    .collect();
                width = width.max(cells.len());
                rows.push(cells);
                next = node.end;
            }
            _ => next += 1,
        }
    }
    if width == 0 {
        return Ok(());
    }
    let headed = rows.first().is_some_and(|cells| {
        cells
            .iter()
            .any(|&i| nodes.get(i).is_some_and(|n| n.kind == b'H'))
    });
    // Width and height are both bounded by MAX_NODES, but their padded
    // product may be much larger than the output budget (or a 32-bit usize).
    let min = (3 * width + 2).saturating_mul(rows.len() + 1 + usize::from(!headed));
    if min > out.remaining {
        return Err(Limit);
    }
    out.push("\n\n")?;
    let mut caption = Buffer::trimmed(out.remaining, false);
    for child in children(nodes, index).filter(|&c| nodes.get(c).is_some_and(|n| n.kind == b'C')) {
        if !caption.text.is_empty() {
            caption.push("\n")?;
        }
        body(nodes, child, &mut caption)?;
    }
    let caption = caption.text.trim_matches(white);
    if !caption.is_empty() {
        out.push(caption)?;
        out.push("\n\n")?;
    }
    let mut rows = rows.iter();
    row(
        nodes,
        if headed {
            rows.next().map(Vec::as_slice).unwrap_or_default()
        } else {
            &[]
        },
        width,
        out,
    )?;
    out.push("| ")?;
    for index in 0..width {
        if index > 0 {
            out.push(" | ")?;
        }
        out.push("---")?;
    }
    out.push(" |\n")?;
    for cells in rows {
        row(nodes, cells, width, out)?;
    }
    out.push("\n")
}

fn row(nodes: &[Node<'_>], cells: &[usize], width: usize, out: &mut Buffer) -> Result<(), Limit> {
    out.push("| ")?;
    for col in 0..width {
        if col > 0 {
            out.push(" | ")?;
        }
        if let Some(&cell) = cells.get(col) {
            let value = fragment(nodes, cell, out)?;
            let value = value.trim_matches(white);
            let mut previous = '\0';
            let mut whitespace = 0;
            let mut has_newline = false;
            for (offset, c) in value.char_indices() {
                if matches!(c, ' ' | '\t' | '\n' | '\r' | '\x0b' | '\x0c') {
                    if whitespace == 0 {
                        whitespace = offset + 1;
                    }
                    has_newline |= c == '\n';
                    continue;
                }
                if whitespace > 0 {
                    if has_newline {
                        out.push(" ⏎ ")?;
                    } else {
                        out.push(value.get(whitespace - 1..offset).ok_or(Limit)?)?;
                    }
                    previous = ' ';
                    whitespace = 0;
                    has_newline = false;
                }
                if c == '|' && previous != '\\' {
                    out.push("\\")?;
                }
                out.character(c)?;
                previous = c;
            }
        }
    }
    out.push(" |\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn markdown(limit: u32, tokens: &[u8]) -> Vec<u8> {
        let mut frame = b"OCM1\x01".to_vec();
        frame.extend_from_slice(&limit.to_le_bytes());
        frame.extend_from_slice(tokens);
        frame
    }

    #[test]
    fn malformed_and_pathological_frames_hit_explicit_bounds() {
        assert_eq!(
            document(&markdown(30, b"t\x01\x00\x00\x00\xff")),
            Err(Error::Malformed)
        );
        assert_eq!(
            document(&markdown(30, b"t\xff\xff\xff\xff")),
            Err(Error::Malformed)
        );
        let mut deep = vec![b'B'; 200];
        deep.extend([0; 200]);
        assert_eq!(document(&markdown(30, &deep)), Err(Error::Malformed));
        assert_eq!(document(&vec![0; MAX_FRAME + 1]), Err(Error::Malformed));
        assert_eq!(
            document(&markdown(64, b"a\x04\0\0\0x\n<>\0")),
            Err(Error::Malformed)
        );
        assert_eq!(
            document(&markdown(64, b"o\x03\0\0\0x\nx\0")),
            Err(Error::Malformed)
        );
    }

    #[test]
    fn escaped_output_budget_counts_utf16_without_splitting_scalars_or_escapes() {
        let mut token = b"t\x05\x00\x00\x00".to_vec();
        token.extend_from_slice("🧅<".as_bytes());
        assert_eq!(
            document(&markdown(2, &token)).unwrap(),
            b"OCM1\x01\x01\x04\x00\x00\x00\xf0\x9f\xa7\x85"
        );
        assert_eq!(
            document(&markdown(1, &token)).unwrap(),
            b"OCM1\x01\x01\x00\x00\x00\x00"
        );
    }

    #[test]
    fn batched_plain_text_preserves_crlf_unicode_and_the_empty_line_contract() {
        let raw = "  a  \r\n \r\n\r\n\u{a0} \r b 🧅 \t\r\n";
        let mut frame = b"OCT1\x01\x00\x00\x00".to_vec();
        frame.extend_from_slice(&(raw.len() as u32).to_le_bytes());
        frame.extend_from_slice(raw.as_bytes());
        let result = document(&frame).unwrap();
        assert_eq!(std::str::from_utf8(&result[12..]).unwrap(), "a\n\n b 🧅");
    }

    #[test]
    fn bounded_seeded_mutations_never_panic_or_exceed_the_wire_limit() {
        let seed = markdown(64, b"BTRHt\x01\x00\x00\x00x\x00\x00\x00\x00");
        let mut state = 0x42ab_ae71_u32;
        for i in 0..30_000 {
            let mut input = seed.clone();
            for _ in 0..1 + i % 12 {
                state ^= state << 13;
                state ^= state >> 17;
                state ^= state << 5;
                let offset = state as usize % input.len();
                input[offset] = (state >> 16) as u8;
            }
            if let Ok(result) = document(&input) {
                assert!(result.len() <= MAX_FRAME);
                assert!(result.starts_with(b"OCM1") || result.starts_with(b"OCT1"));
            }
        }
    }

    #[test]
    fn whitespace_at_the_output_edge_does_not_consume_the_visible_budget() {
        let mut out = Buffer::trimmed(4, true);
        assert_eq!(
            out.push("                    x🧅x                   "),
            Ok(())
        );
        assert_eq!(out.push("\n"), Ok(()));
        assert_eq!(out.text, "x🧅x");
        assert_eq!(out.push("y"), Err(Limit));
    }
}

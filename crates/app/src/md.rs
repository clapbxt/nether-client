//! Modrinth project descriptions (markdown with HTML mixed in) -> blocks the detail page can render:
//! headings, paragraphs and lists as styled text, code, quotes, rules and images (loaded afterwards).

/// kind: 0 text, 1-3 heading, 4 code, 5 image, 6 quote, 7 rule. `text` is inline markdown (or plain for code);
/// `img` is the URL for image blocks.
#[derive(Clone, Debug, Default)]
pub struct Block { pub kind: i32, pub text: String, pub img: String }

/// Turn the HTML that Modrinth bodies often use into markdown (or drop it).
fn html_to_md(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    let b = s.as_bytes();
    while i < s.len() {
        if b[i] == b'<' {
            if let Some(end) = s[i..].find('>') {
                let tag = &s[i + 1..i + end];
                // ASCII lowercase keeps byte offsets the same as `tag` (to_lowercase can change them and panic the slicing)
                let lower = tag.to_ascii_lowercase();
                let name: String = lower.trim_start_matches('/').chars().take_while(|c| c.is_ascii_alphanumeric()).collect();
                let closing = lower.starts_with('/');
                let attr = |a: &str| -> Option<String> {
                    let p = lower.find(&format!("{a}="))?;
                    let rest = &tag[p + a.len() + 1..];
                    let q = rest.chars().next()?;
                    if q == '"' || q == '\'' { rest[1..].find(q).map(|e| rest[1..1 + e].to_string()) } else { Some(rest.split_whitespace().next()?.to_string()) }
                };
                let rep = match name.as_str() {
                    "img" => attr("src").map(|u| format!("\n\n![]({u})\n\n")).unwrap_or_default(),
                    "br" => "\n".into(),
                    "p" | "div" | "center" | "details" | "summary" | "table" | "tr" => "\n\n".into(),
                    "td" | "th" => " ".into(),
                    "li" if !closing => "\n- ".into(),
                    "h1" | "h2" | "h3" | "h4" if !closing => format!("\n\n{} ", "#".repeat(name[1..].parse::<usize>().unwrap_or(2).min(3))),
                    "h1" | "h2" | "h3" | "h4" => "\n\n".into(),
                    "b" | "strong" => "**".into(),
                    "i" | "em" => "*".into(),
                    "code" => "`".into(),
                    "hr" => "\n\n---\n\n".into(),
                    "a" if !closing => attr("href").map(|u| format!("\u{1}{u}\u{2}")).unwrap_or_default(),
                    "a" => "\u{3}".into(),
                    "iframe" => attr("src").map(|u| format!("\n\n[▶ Video]({u})\n\n")).unwrap_or_default(),
                    _ => String::new(),
                };
                out.push_str(&rep);
                i += end + 1;
                continue;
            }
        }
        let ch = s[i..].chars().next().unwrap();
        out.push(ch);
        i += ch.len_utf8();
    }
    // <a href="u">text</a> -> [text](u)
    let mut res = String::with_capacity(out.len());
    let mut rest = out.as_str();
    while let Some(a) = rest.find('\u{1}') {
        res.push_str(&rest[..a]);
        let after = &rest[a + 1..];
        let (Some(m), Some(e)) = (after.find('\u{2}'), after.find('\u{3}')) else { rest = after; continue };
        if e < m { rest = after; continue; }
        let (url, text) = (&after[..m], after[m + 1..e].trim());
        if text.starts_with("![") || text.contains("\n") { res.push_str(text); } else if !text.is_empty() { res.push_str(&format!("[{text}]({url})")); }
        rest = &after[e + 1..];
    }
    res.push_str(rest);
    res.replace(['\u{1}', '\u{2}', '\u{3}'], "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "‹").replace("&gt;", "›").replace("&quot;", "\"")
}

/// Image URLs in a line made only of images (badges, banners, screenshots).
fn only_images(l: &str) -> Option<Vec<String>> {
    let mut urls = vec![];
    let mut s = l.trim();
    while !s.is_empty() {
        // [![alt](img)](link) or ![alt](img)
        let linked = s.starts_with("[![");
        let start = if linked { 1 } else { 0 };
        if !s[start..].starts_with("![") { return None; }
        let open = s[start..].find("](")? + start;
        let close = s[open..].find(')')? + open;
        urls.push(s[open + 2..close].split_whitespace().next()?.to_string());
        s = &s[close + 1..];
        if linked { let c = s.find(')')?; s = &s[c + 1..]; }
        s = s.trim_start();
    }
    (!urls.is_empty()).then_some(urls)
}

/// Remove inline images and anything StyledText can't take from inline markdown.
fn inline(s: &str) -> String {
    let mut t = s.to_string();
    while let Some(a) = t.find("![") {
        let Some(e) = t[a..].find(')') else { break };
        t.replace_range(a..a + e + 1, "");
    }
    t.replace("[]()", "").replace("<", "‹").replace(">", "›").trim().to_string()
}

pub fn blocks(md: &str) -> Vec<Block> {
    let text = html_to_md(md);
    let mut out: Vec<Block> = vec![];
    let mut para: Vec<String> = vec![];
    let mut list = false;
    let flush = |para: &mut Vec<String>, out: &mut Vec<Block>, list: bool| {
        if para.is_empty() { return; }
        let t = if list { para.join("\n") } else { para.join(" ") };
        let t = inline(&t);
        if !t.is_empty() && t != "-" { out.push(Block { kind: 0, text: t, img: String::new() }); }
        para.clear();
    };
    let mut lines = text.lines().peekable();
    while let Some(raw) = lines.next() {
        let l = raw.trim();
        if l.starts_with("```") {
            flush(&mut para, &mut out, list);
            let mut code = vec![];
            for c in lines.by_ref() { if c.trim_start().starts_with("```") { break; } code.push(c.to_string()); }
            out.push(Block { kind: 4, text: code.join("\n"), img: String::new() });
            continue;
        }
        if l.is_empty() { flush(&mut para, &mut out, list); list = false; continue; }
        if l.trim_matches(['-', '*', '_', ' ']).is_empty() && l.len() >= 3 {
            flush(&mut para, &mut out, list);
            out.push(Block { kind: 7, ..Default::default() });
            continue;
        }
        if l.starts_with('#') {
            flush(&mut para, &mut out, list);
            let level = l.chars().take_while(|c| *c == '#').count().clamp(1, 3) as i32;
            let t = inline(l.trim_start_matches('#'));
            if !t.is_empty() { out.push(Block { kind: level, text: t, img: String::new() }); }
            continue;
        }
        if let Some(urls) = only_images(l) {
            flush(&mut para, &mut out, list);
            for u in urls { out.push(Block { kind: 5, text: String::new(), img: u }); }
            continue;
        }
        if let Some(q) = l.strip_prefix('>') {
            flush(&mut para, &mut out, list);
            let t = inline(q.trim_start_matches('>'));
            match out.last_mut() { Some(b) if b.kind == 6 => { b.text.push(' '); b.text.push_str(&t); } _ => out.push(Block { kind: 6, text: t, img: String::new() }) }
            continue;
        }
        if l.starts_with('|') {
            if l.trim_matches(['|', '-', ':', ' ']).is_empty() { continue; }
            flush(&mut para, &mut out, list);
            let cells: Vec<&str> = l.trim_matches('|').split('|').map(str::trim).filter(|c| !c.is_empty()).collect();
            out.push(Block { kind: 0, text: inline(&cells.join("  ·  ")), img: String::new() });
            continue;
        }
        let is_item = l.starts_with("- ") || l.starts_with("* ") || l.starts_with("+ ") || l.split_once(". ").map(|(n, _)| n.chars().all(|c| c.is_ascii_digit()) && !n.is_empty()).unwrap_or(false);
        if is_item != list { flush(&mut para, &mut out, list); list = is_item; }
        let item = match l.strip_prefix("+ ") { Some(rest) => format!("- {rest}"), None => l.to_string() };
        para.push(if list { item } else { l.to_string() });
        if out.len() > 160 { break; }
    }
    flush(&mut para, &mut out, list);
    // drop leading badges (tiny shields etc. are usually the first images) only if many; keep at most 14 images
    let mut imgs = 0;
    out.retain(|b| if b.kind == 5 { imgs += 1; imgs <= 14 } else { true });
    out
}

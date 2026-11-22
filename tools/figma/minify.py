#!/usr/bin/env python3
"""去掉注释与缩进，把拼好的脚本压到 Figma use_figma 的 5 万字符上限以内。

只做两件事：删注释、删行首缩进与空行。换行一律留着，自动分号与字符串内容都不动。
字符串（单引号、双引号、反引号）里的 // 与 /* 按原文保留，引号内的反斜杠转义也要跳过。
"""
import sys


def strip_comments(src):
    out = []
    i = 0
    n = len(src)
    quote = None
    while i < n:
        c = src[i]
        if quote is None:
            if c in "'\"`":
                quote = c
                out.append(c)
                i += 1
                continue
            if c == '/' and i + 1 < n and src[i + 1] == '/':
                while i < n and src[i] != '\n':
                    i += 1
                continue
            if c == '/' and i + 1 < n and src[i + 1] == '*':
                i += 2
                while i + 1 < n and not (src[i] == '*' and src[i + 1] == '/'):
                    i += 1
                i += 2
                continue
            out.append(c)
            i += 1
            continue
        out.append(c)
        if c == '\\' and i + 1 < n:
            out.append(src[i + 1])
            i += 2
            continue
        if c == quote:
            quote = None
        i += 1
    return ''.join(out)


PUNCT = set("()[]{},;:=+-*/<>!&|?")


def compact(src):
    """删行首缩进与空行，再把标点两侧的空格收掉；换行留着，自动分号与行号都还能对上。"""
    lines = [line.strip() for line in strip_comments(src).split('\n')]
    text = '\n'.join(line for line in lines if line)
    out = []
    quote = None
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if quote is not None:
            out.append(c)
            if c == '\\' and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == quote:
                quote = None
            i += 1
            continue
        if c in "'\"`":
            quote = c
            out.append(c)
            i += 1
            continue
        if c in ' \t':
            j = i
            while j < n and text[j] in ' \t':
                j += 1
            prev = out[-1] if out else ''
            nxt = text[j] if j < n else ''
            # 斜杠两侧不动：正则字面量里的空格改了意思就变了。
            if prev != '/' and nxt != '/' and (prev in PUNCT or nxt in PUNCT):
                i = j
                continue
            out.append(' ')
            i = j
            continue
        out.append(c)
        i += 1
    return ''.join(out)


if __name__ == '__main__':
    sys.stdout.write(compact(sys.stdin.read()))

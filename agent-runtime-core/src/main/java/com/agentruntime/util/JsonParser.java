package com.agentruntime.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器：{@link Json} 的逆操作。
 *
 * <p>与 {@code Json} 成对存在的理由相同——{@code core} 保持零第三方依赖。
 * 产物类型：object → {@code LinkedHashMap<String,Object>}；array → {@code ArrayList<Object>}；
 * 整数 → {@code Long}（溢出则 {@code BigDecimal}）；小数 → {@code BigDecimal}（避免浮点噪声）；
 * 字符串 / 布尔 / null 原样。
 *
 * <p>只服务「从 {@code JSONB} 列还原 payload」这一条路径，不是通用 JSON 库：
 * 不支持注释、尾逗号、单引号，遇到非法字符直接抛错并给出位置。
 */
public final class JsonParser {

    private JsonParser() {
    }

    public static Object parse(String text) {
        if (text == null) {
            return null;
        }
        Parser p = new Parser(text);
        p.skipWhitespace();
        if (p.atEnd()) {
            throw new IllegalArgumentException("JSON 为空");
        }
        Object value = p.parseValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new IllegalArgumentException("JSON 尾部有多余内容，位置 " + p.pos + ": " + p.snippet());
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (v == null) {
            return new LinkedHashMap<>();
        }
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        throw new IllegalArgumentException("期望 JSON object，实际是 " + v.getClass().getSimpleName());
    }

    private static final class Parser {
        private final String s;
        private final int len;
        private int pos;

        Parser(String s) {
            this.s = s;
            this.len = s.length();
        }

        boolean atEnd() {
            return pos >= len;
        }

        char peek() {
            if (atEnd()) {
                throw new IllegalArgumentException("JSON 意外结束，位置 " + pos);
            }
            return s.charAt(pos);
        }

        String snippet() {
            int from = Math.max(0, pos - 10);
            int to = Math.min(len, pos + 10);
            return s.substring(from, to);
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        Object parseValue() {
            char c = peek();
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> {
                    expect("true");
                    yield Boolean.TRUE;
                }
                case 'f' -> {
                    expect("false");
                    yield Boolean.FALSE;
                }
                case 'n' -> {
                    expect("null");
                    yield null;
                }
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (!atEnd() && peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new IllegalArgumentException("JSON object 的 key 必须是字符串，位置 " + pos + ": " + snippet());
                }
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') {
                    throw new IllegalArgumentException("JSON object 缺少 ':'，位置 " + pos + ": " + snippet());
                }
                pos++;
                skipWhitespace();
                map.put(key, parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw new IllegalArgumentException("JSON object 缺少 ',' 或 '}'，位置 " + pos + ": " + snippet());
                }
            }
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (!atEnd() && peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw new IllegalArgumentException("JSON array 缺少 ',' 或 ']'，位置 " + pos + ": " + snippet());
                }
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new IllegalArgumentException("JSON 字符串未闭合，位置 " + pos);
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw new IllegalArgumentException("JSON 转义未闭合，位置 " + pos);
                    }
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > len) {
                                throw new IllegalArgumentException("\\uXXXX 不完整，位置 " + pos);
                            }
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException("非法转义 \\" + esc + "，位置 " + pos);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object parseNumber() {
            int start = pos;
            if (!atEnd() && peek() == '-') {
                pos++;
            }
            while (!atEnd()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            if (num.isEmpty() || num.equals("-")) {
                throw new IllegalArgumentException("非法数字起始，位置 " + start + ": " + snippet());
            }
            boolean decimal = num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0;
            if (decimal) {
                return new BigDecimal(num);
            }
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException overflow) {
                return new BigDecimal(num);
            }
        }

        void expect(char c) {
            if (atEnd() || peek() != c) {
                throw new IllegalArgumentException("期望 '" + c + "'，位置 " + pos + ": " + snippet());
            }
            pos++;
        }

        void expect(String word) {
            if (pos + word.length() > len || !s.startsWith(word, pos)) {
                throw new IllegalArgumentException("期望 \"" + word + "\"，位置 " + pos + ": " + snippet());
            }
            pos += word.length();
        }
    }
}

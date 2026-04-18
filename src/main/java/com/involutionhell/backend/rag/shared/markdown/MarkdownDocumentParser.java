package com.involutionhell.backend.rag.shared.markdown;

import com.involutionhell.backend.rag.shared.support.RagJsonCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 解析 Markdown 文档的 frontmatter、标题层级和代码块结构。
 */
@Component
public class MarkdownDocumentParser {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.*\\S)\\s*$");
    private static final Pattern FENCE_PATTERN = Pattern.compile("^([`~]{3,})([^`]*)$");
    private static final Pattern BLOCKQUOTE_PATTERN = Pattern.compile("^\\s{0,3}>.*$");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)]\\((<?[^)\\s>]+>?)(?:\\s+\"[^\"]*\")?\\)");

    private final RagJsonCodec jsonCodec;

    public MarkdownDocumentParser(RagJsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    /**
     * 解析原始 Markdown 内容，提取 frontmatter 和结构化块。
     */
    public MarkdownDocument parse(String rawContent) {
        String normalized = normalizeNewlines(rawContent);
        FrontmatterExtraction extraction = extractFrontmatter(normalized);
        String body = extraction.body().trim();
        return new MarkdownDocument(
                normalized,
                body,
                extraction.frontmatter(),
                parseBlocks(body)
        );
    }

    private List<MarkdownBlock> parseBlocks(String body) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }

        String[] lines = body.split("\n", -1);
        List<MarkdownBlock> blocks = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        StringBuilder proseBuffer = new StringBuilder();
        StringBuilder codeBuffer = null;
        String codeFence = null;
        String codeLanguage = null;

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (codeBuffer != null) {
                if (isClosingFence(line, codeFence)) {
                    addCodeBlock(blocks, headingPath, codeBuffer.toString(), codeLanguage);
                    codeBuffer = null;
                    codeFence = null;
                    codeLanguage = null;
                } else {
                    codeBuffer.append(line).append('\n');
                }
                continue;
            }

            Matcher headingMatcher = HEADING_PATTERN.matcher(line);
            if (headingMatcher.matches()) {
                addTextBlock(blocks, headingPath, proseBuffer.toString());
                proseBuffer.setLength(0);
                updateHeadingPath(headingPath, headingMatcher.group(1).length(), headingMatcher.group(2).trim());
                continue;
            }

            Matcher fenceMatcher = FENCE_PATTERN.matcher(line.trim());
            if (fenceMatcher.matches()) {
                addTextBlock(blocks, headingPath, proseBuffer.toString());
                proseBuffer.setLength(0);
                codeBuffer = new StringBuilder();
                codeFence = fenceMatcher.group(1);
                codeLanguage = normalizeCodeLanguage(fenceMatcher.group(2));
                continue;
            }

            if (isTableStart(lines, index)) {
                addTextBlock(blocks, headingPath, proseBuffer.toString());
                proseBuffer.setLength(0);
                index = addTableBlock(blocks, headingPath, lines, index);
                continue;
            }

            if (isBlockquoteLine(line)) {
                addTextBlock(blocks, headingPath, proseBuffer.toString());
                proseBuffer.setLength(0);
                index = addBlockquoteBlock(blocks, headingPath, lines, index);
                continue;
            }

            proseBuffer.append(line).append('\n');
        }

        if (codeBuffer != null) {
            addCodeBlock(blocks, headingPath, codeBuffer.toString(), codeLanguage);
        }
        addTextBlock(blocks, headingPath, proseBuffer.toString());
        return blocks;
    }

    private FrontmatterExtraction extractFrontmatter(String content) {
        String[] lines = content.split("\n", -1);
        if (lines.length < 3 || !lines[0].trim().equals("---")) {
            return new FrontmatterExtraction(content, Map.of());
        }

        for (int index = 1; index < lines.length; index++) {
            String delimiter = lines[index].trim();
            if (!delimiter.equals("---") && !delimiter.equals("...")) {
                continue;
            }

            String yamlText = String.join("\n", List.of(lines).subList(1, index));
            String body = String.join("\n", List.of(lines).subList(index + 1, lines.length));
            return new FrontmatterExtraction(body, parseFrontmatter(yamlText));
        }

        return new FrontmatterExtraction(content, Map.of());
    }

    private Map<String, Object> parseFrontmatter(String yamlText) {
        if (!StringUtils.hasText(yamlText)) {
            return Map.of();
        }

        try {
            LoaderOptions loaderOptions = new LoaderOptions();
            Object loaded = new Yaml(new SafeConstructor(loaderOptions)).load(yamlText);
            if (!(loaded instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, Object> converted = jsonCodec.convertToMap(map);
            return converted == null ? Map.of() : converted;
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private void addTextBlock(List<MarkdownBlock> blocks, List<String> headingPath, String text) {
        String normalized = text.trim();
        if (!StringUtils.hasText(normalized)) {
            return;
        }
        blocks.add(new MarkdownBlock(
                List.copyOf(headingPath),
                "text",
                null,
                normalized,
                buildMetadata(normalized)
        ));
    }

    private void addCodeBlock(
            List<MarkdownBlock> blocks,
            List<String> headingPath,
            String code,
            String codeLanguage
    ) {
        String normalized = stripTrailingNewlines(code);
        if (!StringUtils.hasText(normalized)) {
            return;
        }
        blocks.add(new MarkdownBlock(
                List.copyOf(headingPath),
                "code",
                codeLanguage,
                normalized,
                Map.of()
        ));
    }

    private int addTableBlock(List<MarkdownBlock> blocks, List<String> headingPath, String[] lines, int startIndex) {
        List<String> tableLines = new ArrayList<>();
        int index = startIndex;
        while (index < lines.length && isTableLine(lines[index])) {
            tableLines.add(lines[index]);
            index++;
        }

        String content = String.join("\n", tableLines).trim();
        if (!StringUtils.hasText(content)) {
            return index - 1;
        }

        Map<String, Object> metadata = new LinkedHashMap<>(buildMetadata(content));
        List<String> columns = parseTableColumns(tableLines.getFirst());
        metadata.put("tableColumns", columns);
        metadata.put("tableRowCount", Math.max(0, tableLines.size() - 2));
        metadata.put("tableRows", parseTableRows(tableLines, columns));
        blocks.add(new MarkdownBlock(
                List.copyOf(headingPath),
                "table",
                null,
                content,
                metadata
        ));
        return index - 1;
    }

    private int addBlockquoteBlock(List<MarkdownBlock> blocks, List<String> headingPath, String[] lines, int startIndex) {
        List<String> quoteLines = new ArrayList<>();
        int maxDepth = 0;
        int index = startIndex;
        while (index < lines.length && isBlockquoteLine(lines[index])) {
            String line = lines[index];
            quoteLines.add(line);
            maxDepth = Math.max(maxDepth, countBlockquoteDepth(line));
            index++;
        }

        String content = String.join("\n", quoteLines).trim();
        if (!StringUtils.hasText(content)) {
            return index - 1;
        }

        Map<String, Object> metadata = new LinkedHashMap<>(buildMetadata(content));
        metadata.put("quoteDepth", maxDepth);
        blocks.add(new MarkdownBlock(
                List.copyOf(headingPath),
                "blockquote",
                null,
                content,
                metadata
        ));
        return index - 1;
    }

    private boolean isClosingFence(String line, String openingFence) {
        String trimmed = line.trim();
        if (!StringUtils.hasText(trimmed) || openingFence == null) {
            return false;
        }
        char marker = openingFence.charAt(0);
        int count = 0;
        while (count < trimmed.length() && trimmed.charAt(count) == marker) {
            count++;
        }
        return count >= openingFence.length() && trimmed.substring(count).isBlank();
    }

    private boolean isTableStart(String[] lines, int index) {
        return index + 1 < lines.length
                && isTableLine(lines[index])
                && isTableSeparator(lines[index + 1]);
    }

    private boolean isTableLine(String line) {
        return StringUtils.hasText(line) && line.contains("|");
    }

    private boolean isTableSeparator(String line) {
        List<String> cells = parseTableCells(line);
        if (cells.isEmpty()) {
            return false;
        }
        return cells.stream().allMatch(cell -> cell.matches(":?-{3,}:?"));
    }

    private List<String> parseTableColumns(String headerLine) {
        return parseTableCells(headerLine).stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<Map<String, Object>> parseTableRows(List<String> tableLines, List<String> columns) {
        if (tableLines.size() <= 2 || columns.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int rowIndex = 2; rowIndex < tableLines.size(); rowIndex++) {
            List<String> cells = parseTableCells(tableLines.get(rowIndex));
            if (cells.isEmpty()) {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rowIndex", rowIndex - 2);

            Map<String, String> values = new LinkedHashMap<>();
            for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                String columnName = columns.get(columnIndex);
                String rawValue = columnIndex < cells.size() ? cells.get(columnIndex) : "";
                values.put(columnName, normalizeTableCellValue(rawValue));
            }
            row.put("values", values);

            List<Map<String, String>> links = extractLinks(tableLines.get(rowIndex));
            if (!links.isEmpty()) {
                row.put("links", links);
            }
            rows.add(row);
        }
        return rows;
    }

    private List<String> parseTableCells(String line) {
        String normalized = line.trim();
        if (normalized.startsWith("|")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("|")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        return List.of(normalized.split("\\|", -1)).stream()
                .map(String::trim)
                .toList();
    }

    private boolean isBlockquoteLine(String line) {
        return BLOCKQUOTE_PATTERN.matcher(line).matches();
    }

    private int countBlockquoteDepth(String line) {
        String trimmed = line.stripLeading();
        int depth = 0;
        while (depth < trimmed.length() && trimmed.charAt(depth) == '>') {
            depth++;
        }
        return depth;
    }

    private void updateHeadingPath(List<String> headingPath, int level, String heading) {
        while (headingPath.size() >= level) {
            headingPath.removeLast();
        }
        headingPath.add(heading);
    }

    private String normalizeCodeLanguage(String rawLanguage) {
        if (!StringUtils.hasText(rawLanguage)) {
            return null;
        }
        String trimmed = rawLanguage.trim();
        int separator = trimmed.indexOf(' ');
        return separator >= 0 ? trimmed.substring(0, separator) : trimmed;
    }

    private String stripTrailingNewlines(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '\n' || value.charAt(end - 1) == '\r')) {
            end--;
        }
        return value.substring(0, end);
    }

    private Map<String, Object> buildMetadata(String content) {
        List<Map<String, String>> links = extractLinks(content);
        if (links.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("links", links);
        return metadata;
    }

    private List<Map<String, String>> extractLinks(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }

        List<Map<String, String>> links = new ArrayList<>();
        Matcher matcher = LINK_PATTERN.matcher(content);
        while (matcher.find()) {
            String text = matcher.group(1).trim();
            String rawUrl = matcher.group(2).trim();
            String url = rawUrl.startsWith("<") && rawUrl.endsWith(">")
                    ? rawUrl.substring(1, rawUrl.length() - 1)
                    : rawUrl;

            if (!text.isBlank() && !url.isBlank()) {
                Map<String, String> link = new LinkedHashMap<>();
                link.put("text", text);
                link.put("url", url);
                links.add(link);
            }
        }
        return links;
    }

    private String normalizeTableCellValue(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return "";
        }

        String normalized = rawValue.trim();
        Matcher matcher = LINK_PATTERN.matcher(normalized);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String text = matcher.group(1).trim();
            String rawUrl = matcher.group(2).trim();
            String url = rawUrl.startsWith("<") && rawUrl.endsWith(">")
                    ? rawUrl.substring(1, rawUrl.length() - 1)
                    : rawUrl;
            String replacement = text.isBlank() ? url : text + " (" + url + ")";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString().replaceAll("\\s+", " ").trim();
    }

    private String normalizeNewlines(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * Markdown 解析结果。
     *
     * @param rawContent 原始 Markdown 内容
     * @param bodyContent 去除 frontmatter 后的正文
     * @param frontmatter frontmatter 解析结果
     * @param blocks 结构化块列表
     */
    public record MarkdownDocument(
            String rawContent,
            String bodyContent,
            Map<String, Object> frontmatter,
            List<MarkdownBlock> blocks
    ) {
    }

    /**
     * Markdown 结构化块，按正文和代码块分离。
     *
     * @param headingPath 当前块的标题路径
     * @param blockType 块类型
     * @param codeLanguage 代码语言
     * @param content 块正文
     * @param blockMetadata 块级附加元数据
     */
    public record MarkdownBlock(
            List<String> headingPath,
            String blockType,
            String codeLanguage,
            String content,
            Map<String, Object> blockMetadata
    ) {
    }

    /**
     * Frontmatter 提取结果。
     *
     * @param body 去除 frontmatter 后的正文
     * @param frontmatter 提取出的 frontmatter 数据
     */
    private record FrontmatterExtraction(String body, Map<String, Object> frontmatter) {
    }
}

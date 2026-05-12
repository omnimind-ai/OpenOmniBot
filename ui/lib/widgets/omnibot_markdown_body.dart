import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_math_fork/flutter_math.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:markdown/markdown.dart' as md;
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/chat_drawer_gesture_guard.dart';
import 'package:ui/widgets/omnibot_resource_widgets.dart';

class OmnibotMarkdownBody extends StatelessWidget {
  static const String _trailingInlineToken = '[[omnibot-trailing-inline]]';

  final String data;
  final TextStyle baseStyle;
  final bool selectable;
  final bool inlineResourcePlainStyle;
  final Widget? trailingInline;
  final OmnibotResourceOpenCallback? onResourceOpen;

  const OmnibotMarkdownBody({
    super.key,
    required this.data,
    required this.baseStyle,
    this.selectable = false,
    this.inlineResourcePlainStyle = false,
    this.trailingInline,
    this.onResourceOpen,
  });

  @override
  Widget build(BuildContext context) {
    final codeTapHandler = OmnibotCodeTapHandler();
    final styleSheet = buildOmnibotMarkdownStyleSheet(context, baseStyle);
    return MarkdownBody(
      data: _linkifyBareOmnibotUris(_withTrailingInlineToken(data)),
      selectable: selectable,
      onTapLink: (text, href, title) {
        if (href == null) return;
        _handleMarkdownLinkTap(context, href, onResourceOpen);
      },
      blockSyntaxes: const <md.BlockSyntax>[
        OmnibotTableSyntax(),
        OmnibotMathBlockSyntax(),
      ],
      inlineSyntaxes: <md.InlineSyntax>[
        OmnibotInlineMathSyntax(),
        OmnibotInlineLinkSyntax(),
        if (trailingInline != null) OmnibotTrailingInlineSyntax(),
      ],
      builders: buildOmnibotMarkdownBuilders(
        baseStyle: baseStyle,
        selectable: selectable,
        inlineResourcePlainStyle: inlineResourcePlainStyle,
        codeTapHandler: codeTapHandler,
        trailingInline: trailingInline,
        onResourceOpen: onResourceOpen,
      ),
      sizedImageBuilder: (config) {
        final uri = config.uri;
        if (uri.scheme == 'omnibot') {
          final metadata = OmnibotResourceService.resolveUri(uri.toString());
          if (metadata != null) {
            return OmnibotInlineResourceEmbed(
              metadata: metadata,
              plainStyle: inlineResourcePlainStyle,
              onOpen: onResourceOpen,
            );
          }
        }
        if (uri.scheme == 'file') {
          return Image.file(File.fromUri(uri));
        }
        return Image.network(uri.toString());
      },
      styleSheet: styleSheet,
    );
  }

  String _withTrailingInlineToken(String source) {
    if (trailingInline == null) {
      return source;
    }
    // 只去除尾部换行（避免 token 被推入新段落），保留原始空格，
    // 不人为添加额外空格，使 markdown 前缀与纯文本尾部无缝衔接。
    final trimmed = source.replaceFirst(RegExp(r'[\r\n]+$'), '');
    if (trimmed.isEmpty) {
      return _trailingInlineToken;
    }
    return '$trimmed$_trailingInlineToken';
  }
}

MarkdownStyleSheet buildOmnibotMarkdownStyleSheet(
  BuildContext context,
  TextStyle baseStyle,
) {
  final baseColor = baseStyle.color;
  TextStyle headingStyle(double fontSize) => baseStyle.copyWith(
    fontSize: fontSize,
    fontWeight: FontWeight.bold,
    color: baseColor,
  );

  return MarkdownStyleSheet.fromTheme(Theme.of(context)).copyWith(
    p: baseStyle.copyWith(height: 1.5),
    h1: headingStyle(24),
    h2: headingStyle(20),
    h3: headingStyle(18),
    h4: headingStyle(16),
    h5: headingStyle(15),
    h6: headingStyle(baseStyle.fontSize ?? 14),
    code: baseStyle.copyWith(
      fontFamily: 'monospace',
      fontSize: (baseStyle.fontSize ?? 14) * 0.92,
      backgroundColor: Colors.transparent,
      color: Theme.of(context).colorScheme.onSurfaceVariant,
    ),
    codeblockDecoration: BoxDecoration(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(14),
    ),
    blockquoteDecoration: BoxDecoration(
      color: Colors.grey.withValues(alpha: 0.1),
      border: Border(
        left: BorderSide(
          color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.5),
          width: 4,
        ),
      ),
    ),
    tableColumnWidth: const IntrinsicColumnWidth(),
    tableCellsPadding: const EdgeInsets.all(6),
    tableHead: baseStyle.copyWith(
      color: baseColor,
      fontWeight: FontWeight.w600,
    ),
    tableBody: baseStyle.copyWith(color: baseColor),
  );
}

Map<String, MarkdownElementBuilder> buildOmnibotMarkdownBuilders({
  required TextStyle baseStyle,
  required bool selectable,
  required bool inlineResourcePlainStyle,
  required OmnibotCodeTapHandler codeTapHandler,
  Widget? trailingInline,
  OmnibotResourceOpenCallback? onResourceOpen,
}) {
  return <String, MarkdownElementBuilder>{
    'code': OmnibotInlineCodeBuilder(onCopy: codeTapHandler.copy),
    'pre': OmnibotCodeBlockBuilder(onCopy: codeTapHandler.copy),
    'math-inline': OmnibotInlineMathBuilder(baseStyle: baseStyle),
    'math-block': OmnibotBlockMathBuilder(baseStyle: baseStyle),
    'omnibot-table': OmnibotTableBuilder(
      baseStyle: baseStyle,
      selectable: selectable,
      inlineResourcePlainStyle: inlineResourcePlainStyle,
      onResourceOpen: onResourceOpen,
    ),
    'omnibot-link': OmnibotInlineLinkBuilder(
      inlineResourcePlainStyle: inlineResourcePlainStyle,
      onResourceOpen: onResourceOpen,
    ),
    if (trailingInline != null)
      'omnibot-trailing-inline': OmnibotTrailingInlineBuilder(
        child: trailingInline,
      ),
  };
}

class OmnibotTableSyntax extends md.BlockSyntax {
  static const String tag = 'omnibot-table';
  static const String payloadAttribute = 'data-omnibot-table-payload';
  static final RegExp _tableDividerPattern = RegExp(
    r'^[ ]{0,3}\|?([ \t]*:?\-+:?[ \t]*\|[ \t]*)+([ \t]|[ \t]*:?\-+:?[ \t]*)?$',
  );

  const OmnibotTableSyntax();

  @override
  bool canEndBlock(md.BlockParser parser) => true;

  @override
  RegExp get pattern => RegExp('');

  @override
  bool canParse(md.BlockParser parser) {
    return parser.matchesNext(_tableDividerPattern);
  }

  @override
  md.Node? parse(md.BlockParser parser) {
    final alignments = _parseAlignments(parser.next!.content);
    final columnCount = alignments.length;
    final headRow = _parseRowCells(parser, alignments);
    if (headRow.length != columnCount) {
      parser.retreat();
      return null;
    }

    parser.advance();

    final rows = <_OmnibotTableRowSpec>[
      _OmnibotTableRowSpec(isHeader: true, cells: headRow),
    ];
    while (!parser.isDone && !md.BlockSyntax.isAtBlockEnd(parser)) {
      final row = _parseRowCells(parser, alignments);
      while (row.length < columnCount) {
        row.add(const _OmnibotTableCellSpec(source: ''));
      }
      while (row.length > columnCount) {
        row.removeLast();
      }
      rows.add(_OmnibotTableRowSpec(isHeader: false, cells: row));
    }

    return md.Element.empty(tag)
      ..attributes[payloadAttribute] = jsonEncode(
        rows.map((row) => row.toJson()).toList(growable: false),
      );
  }

  List<String?> _parseAlignments(String line) {
    final columns = <String?>[];
    var started = false;
    var hitDash = false;
    String? alignment;

    for (var index = 0; index < line.length; index++) {
      final char = line.codeUnitAt(index);
      if (char == 32 || char == 9 || (!started && char == 124)) {
        continue;
      }
      started = true;

      if (char == 58) {
        if (hitDash) {
          alignment = alignment == 'left' ? 'center' : 'right';
        } else {
          alignment = 'left';
        }
      }

      if (char == 124) {
        columns.add(alignment);
        hitDash = false;
        alignment = null;
      } else {
        hitDash = true;
      }
    }

    if (hitDash) {
      columns.add(alignment);
    }

    return columns;
  }

  List<_OmnibotTableCellSpec> _parseRowCells(
    md.BlockParser parser,
    List<String?> alignments,
  ) {
    final line = parser.current;
    final cells = <String>[];
    var index = _walkPastOpeningPipe(line.content);
    final cellBuffer = StringBuffer();

    while (true) {
      if (index >= line.content.length) {
        cells.add(cellBuffer.toString().trimRight());
        cellBuffer.clear();
        break;
      }
      final char = line.content.codeUnitAt(index);
      if (char == 92) {
        if (index == line.content.length - 1) {
          cellBuffer.writeCharCode(char);
          cells.add(cellBuffer.toString().trimRight());
          cellBuffer.clear();
          break;
        }
        final escaped = line.content.codeUnitAt(index + 1);
        if (escaped == 124) {
          cellBuffer.writeCharCode(escaped);
        } else {
          cellBuffer.writeCharCode(char);
          cellBuffer.writeCharCode(escaped);
        }
        index += 2;
      } else if (char == 124) {
        cells.add(cellBuffer.toString().trimRight());
        cellBuffer.clear();
        index++;
        index = _walkPastWhitespace(line.content, index);
        if (index >= line.content.length) {
          break;
        }
      } else {
        cellBuffer.writeCharCode(char);
        index++;
      }
    }
    parser.advance();

    final rowChildren = <_OmnibotTableCellSpec>[];
    for (var index = 0; index < cells.length; index++) {
      rowChildren.add(
        _OmnibotTableCellSpec(
          source: cells[index],
          align: index < alignments.length ? alignments[index] : null,
        ),
      );
    }

    return rowChildren;
  }

  int _walkPastWhitespace(String line, int index) {
    while (index < line.length) {
      final char = line.codeUnitAt(index);
      if (char != 32 && char != 9) {
        break;
      }
      index++;
    }
    return index;
  }

  int _walkPastOpeningPipe(String line) {
    var index = 0;
    while (index < line.length) {
      final char = line.codeUnitAt(index);
      if (char == 124) {
        index++;
        index = _walkPastWhitespace(line, index);
      }
      if (char != 32 && char != 9) {
        break;
      }
      index++;
    }
    return index;
  }
}

class OmnibotInlineLinkSyntax extends md.InlineSyntax {
  OmnibotInlineLinkSyntax() : super(_pattern);

  static const String _pattern = r'(?<!!)\[([^\]]*?)\]\((omnibot://[^)\s]+)\)';

  @override
  bool onMatch(md.InlineParser parser, Match match) {
    final label = match[1] ?? '';
    final href = match[2] ?? '';
    final element = md.Element.text(
      'omnibot-link',
      label.isEmpty ? href : label,
    )..attributes['href'] = href;
    parser.addNode(element);
    return true;
  }
}

class OmnibotMathBlockSyntax extends md.BlockSyntax {
  static const String expressionAttribute = 'data-expression';

  const OmnibotMathBlockSyntax();

  @override
  RegExp get pattern => RegExp(r'^\s*\$\$');

  @override
  bool canParse(md.BlockParser parser) {
    return pattern.hasMatch(parser.current.content);
  }

  @override
  md.Node parse(md.BlockParser parser) {
    final firstLineTrimmed = parser.current.content.trim();
    if (firstLineTrimmed.startsWith(r'$$') &&
        firstLineTrimmed.endsWith(r'$$') &&
        firstLineTrimmed.length > 4) {
      final inlineExpression =
          firstLineTrimmed.substring(2, firstLineTrimmed.length - 2).trim();
      parser.advance();
      return _buildMathElement(inlineExpression);
    }

    final expressionBuffer = StringBuffer();
    final firstRemainder = firstLineTrimmed.substring(2).trimRight();
    if (firstRemainder.isNotEmpty) {
      expressionBuffer.write(firstRemainder);
    }
    parser.advance();

    while (!parser.isDone) {
      final line = parser.current.content;
      final lineTrimmedRight = line.trimRight();
      final normalized = lineTrimmedRight.trim();

      if (normalized == r'$$') {
        parser.advance();
        break;
      }

      if (normalized.endsWith(r'$$')) {
        final closeIndex = lineTrimmedRight.lastIndexOf(r'$$');
        final contentBeforeClose = lineTrimmedRight.substring(0, closeIndex);
        if (expressionBuffer.isNotEmpty) {
          expressionBuffer.writeln();
        }
        expressionBuffer.write(contentBeforeClose.trimRight());
        parser.advance();
        break;
      }

      if (expressionBuffer.isNotEmpty) {
        expressionBuffer.writeln();
      }
      expressionBuffer.write(lineTrimmedRight);
      parser.advance();
    }

    return _buildMathElement(expressionBuffer.toString().trim());
  }

  md.Element _buildMathElement(String expression) {
    final element = md.Element.empty('math-block');
    element.attributes[expressionAttribute] = expression;
    return element;
  }
}

class OmnibotTableBuilder extends MarkdownElementBuilder {
  OmnibotTableBuilder({
    required this.baseStyle,
    required this.selectable,
    required this.inlineResourcePlainStyle,
    this.onResourceOpen,
  });

  final TextStyle baseStyle;
  final bool selectable;
  final bool inlineResourcePlainStyle;
  final OmnibotResourceOpenCallback? onResourceOpen;

  @override
  bool isBlockElement() => true;

  @override
  Widget visitElementAfterWithContext(
    BuildContext context,
    md.Element element,
    TextStyle? preferredStyle,
    TextStyle? parentStyle,
  ) {
    final styleSheet = buildOmnibotMarkdownStyleSheet(context, baseStyle);
    final tableRows = _buildTableRows(styleSheet, element);
    if (tableRows.isEmpty) {
      return const SizedBox.shrink();
    }
    return Padding(
      padding: styleSheet.tablePadding ?? EdgeInsets.zero,
      child: _OmnibotTableScrollable(
        thumbVisibility: styleSheet.tableScrollbarThumbVisibility ?? false,
        child: Table(
          border: styleSheet.tableBorder,
          defaultColumnWidth:
              styleSheet.tableColumnWidth ?? const IntrinsicColumnWidth(),
          defaultVerticalAlignment: styleSheet.tableVerticalAlignment,
          children: tableRows,
        ),
      ),
    );
  }

  List<TableRow> _buildTableRows(
    MarkdownStyleSheet styleSheet,
    md.Element tableElement,
  ) {
    final payload =
        tableElement.attributes[OmnibotTableSyntax.payloadAttribute];
    if (payload == null || payload.isEmpty) {
      return const <TableRow>[];
    }
    final decoded = jsonDecode(payload);
    if (decoded is! List) {
      return const <TableRow>[];
    }
    final rowSpecs = decoded
        .whereType<Map<String, dynamic>>()
        .map(_OmnibotTableRowSpec.fromJson)
        .toList(growable: false);
    final rows = <TableRow>[];
    for (final rowSpec in rowSpecs) {
      rows.add(TableRow(children: _buildRowCells(styleSheet, rowSpec)));
    }
    return rows;
  }

  List<Widget> _buildRowCells(
    MarkdownStyleSheet styleSheet,
    _OmnibotTableRowSpec rowSpec,
  ) {
    if (rowSpec.cells.isEmpty) {
      return const <Widget>[SizedBox.shrink()];
    }
    return rowSpec.cells
        .map((cell) {
          final isHeader = rowSpec.isHeader;
          final cellStyle =
              (isHeader ? styleSheet.tableHead : styleSheet.tableBody) ??
              baseStyle;
          final textAlign = _resolveCellTextAlign(
            cell.align,
            fallback:
                isHeader
                    ? (styleSheet.tableHeadAlign ?? TextAlign.center)
                    : TextAlign.left,
          );
          final alignment = _alignmentForTextAlign(textAlign);
          return DecoratedBox(
            decoration:
                styleSheet.tableCellsDecoration ?? const BoxDecoration(),
            child: Padding(
              padding: styleSheet.tableCellsPadding ?? EdgeInsets.zero,
              child: Align(
                alignment: alignment,
                child: _OmnibotMarkdownTableCell(
                  data: cell.source,
                  baseStyle: cellStyle,
                  selectable: selectable,
                  inlineResourcePlainStyle: inlineResourcePlainStyle,
                  onResourceOpen: onResourceOpen,
                  textAlign: textAlign,
                ),
              ),
            ),
          );
        })
        .toList(growable: false);
  }

  TextAlign _resolveCellTextAlign(
    String? align, {
    required TextAlign fallback,
  }) {
    return switch (align) {
      'left' => TextAlign.left,
      'center' => TextAlign.center,
      'right' => TextAlign.right,
      _ => fallback,
    };
  }

  Alignment _alignmentForTextAlign(TextAlign textAlign) {
    return switch (textAlign) {
      TextAlign.center => Alignment.center,
      TextAlign.right || TextAlign.end => Alignment.centerRight,
      _ => Alignment.centerLeft,
    };
  }
}

class _OmnibotTableRowSpec {
  const _OmnibotTableRowSpec({required this.isHeader, required this.cells});

  factory _OmnibotTableRowSpec.fromJson(Map<String, dynamic> json) {
    final rawCells = json['cells'];
    final cells =
        rawCells is List
            ? rawCells
                .whereType<Map<String, dynamic>>()
                .map(_OmnibotTableCellSpec.fromJson)
                .toList(growable: false)
            : const <_OmnibotTableCellSpec>[];
    return _OmnibotTableRowSpec(
      isHeader: json['isHeader'] == true,
      cells: cells,
    );
  }

  final bool isHeader;
  final List<_OmnibotTableCellSpec> cells;

  Map<String, Object?> toJson() {
    return <String, Object?>{
      'isHeader': isHeader,
      'cells': cells.map((cell) => cell.toJson()).toList(growable: false),
    };
  }
}

class _OmnibotTableCellSpec {
  const _OmnibotTableCellSpec({required this.source, this.align});

  factory _OmnibotTableCellSpec.fromJson(Map<String, dynamic> json) {
    return _OmnibotTableCellSpec(
      source: json['source'] as String? ?? '',
      align: json['align'] as String?,
    );
  }

  final String source;
  final String? align;

  Map<String, Object?> toJson() {
    return <String, Object?>{'source': source, 'align': align};
  }
}

class _OmnibotTableScrollable extends StatefulWidget {
  const _OmnibotTableScrollable({
    required this.thumbVisibility,
    required this.child,
  });

  final bool thumbVisibility;
  final Widget child;

  @override
  State<_OmnibotTableScrollable> createState() =>
      _OmnibotTableScrollableState();
}

class _OmnibotTableScrollableState extends State<_OmnibotTableScrollable> {
  late final ScrollController _scrollController;

  @override
  void initState() {
    super.initState();
    _scrollController = ScrollController();
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ChatDrawerGestureGuard(
      child: Scrollbar(
        controller: _scrollController,
        thumbVisibility: widget.thumbVisibility,
        child: SingleChildScrollView(
          controller: _scrollController,
          scrollDirection: Axis.horizontal,
          child: widget.child,
        ),
      ),
    );
  }
}

class _OmnibotMarkdownTableCell extends StatelessWidget {
  const _OmnibotMarkdownTableCell({
    required this.data,
    required this.baseStyle,
    required this.selectable,
    required this.inlineResourcePlainStyle,
    required this.onResourceOpen,
    required this.textAlign,
  });

  final String data;
  final TextStyle baseStyle;
  final bool selectable;
  final bool inlineResourcePlainStyle;
  final OmnibotResourceOpenCallback? onResourceOpen;
  final TextAlign textAlign;

  @override
  Widget build(BuildContext context) {
    return DefaultTextStyle.merge(
      style: baseStyle,
      textAlign: textAlign,
      child: OmnibotMarkdownBody(
        data: _tableCellInlineMarkdown(data),
        baseStyle: baseStyle,
        selectable: selectable,
        inlineResourcePlainStyle: inlineResourcePlainStyle,
        onResourceOpen: onResourceOpen,
      ),
    );
  }
}

// Markdown table cells are inline content. Keep leading markers like "-" from
// becoming block widgets that cannot be measured by an intrinsic-width table.
String _tableCellInlineMarkdown(String source) {
  if (source.isEmpty) {
    return source;
  }

  return _escapeLeadingMathBlockMarker(
    _escapeLeadingOrderedListMarker(_escapeLeadingBlockMarker(source)),
  );
}

String _escapeLeadingBlockMarker(String source) {
  return source
      .replaceFirstMapped(
        RegExp(
          r'^([ \t]{0,3})(#{1,6}(?=[ \t]|$)|[-+*](?=[ \t]+|$)|>{1}|`{3,}|~{3,}|\[[^\]\n]+]:)',
        ),
        (match) {
          final marker = match[2]!;
          return '${match[1]}${_htmlEntityForCodeUnit(marker.codeUnitAt(0))}'
              '${marker.substring(1)}';
        },
      )
      .replaceFirstMapped(
        RegExp(
          r'^([ \t]{0,3})((?:-[ \t]*){3,}|(?:_[ \t]*){3,}|(?:\*[ \t]*){3,})$',
        ),
        (match) {
          final marker = match[2]!;
          return '${match[1]}${_htmlEntityForCodeUnit(marker.codeUnitAt(0))}'
              '${marker.substring(1)}';
        },
      );
}

String _escapeLeadingOrderedListMarker(String source) {
  return source.replaceFirstMapped(
    RegExp(r'^([ \t]{0,3}\d{1,9})([.)])(?=[ \t]+)'),
    (match) => '${match[1]}${_htmlEntityForCodeUnit(match[2]!.codeUnitAt(0))}',
  );
}

String _escapeLeadingMathBlockMarker(String source) {
  return source.replaceFirstMapped(
    RegExp(r'^([ \t]*)(\$\$)'),
    (match) => '${match[1]}&#36;${match[2]!.substring(1)}',
  );
}

String _htmlEntityForCodeUnit(int codeUnit) => '&#$codeUnit;';

class OmnibotInlineMathSyntax extends md.InlineSyntax {
  OmnibotInlineMathSyntax() : super(_pattern);

  static const String _pattern = r'(?<!\\)(?<!\$)\$([^\$\n]+?)\$(?!\$)';

  @override
  bool onMatch(md.InlineParser parser, Match match) {
    final expression = match[1] ?? '';
    parser.addNode(md.Element.text('math-inline', expression));
    return true;
  }
}

typedef OmnibotCodeCopyCallback = Future<void> Function(String code);

class OmnibotCodeTapHandler {
  const OmnibotCodeTapHandler();

  Future<void> copy(String code) async {
    if (code.trim().isEmpty) return;
    try {
      await Clipboard.setData(ClipboardData(text: code));
      showToast(
        LegacyTextLocalizer.isEnglish ? 'Code copied' : '代码已复制',
        type: ToastType.success,
      );
    } catch (_) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Copy failed, please try again'
            : '复制失败，请重试',
        type: ToastType.error,
      );
    }
  }
}

class OmnibotInlineCodeBuilder extends MarkdownElementBuilder {
  OmnibotInlineCodeBuilder({required this.onCopy});

  final OmnibotCodeCopyCallback onCopy;

  @override
  Widget visitElementAfterWithContext(
    BuildContext context,
    md.Element element,
    TextStyle? preferredStyle,
    TextStyle? parentStyle,
  ) {
    final code = element.textContent;
    if (code.isEmpty) {
      return const SizedBox.shrink();
    }
    final theme = Theme.of(context);
    final borderRadius = BorderRadius.circular(8);
    final codeStyle = (preferredStyle ?? parentStyle ?? const TextStyle())
        .copyWith(
          fontFamily: 'monospace',
          backgroundColor: Colors.transparent,
          color: theme.colorScheme.onSurfaceVariant,
          fontSize:
              ((preferredStyle?.fontSize ?? parentStyle?.fontSize ?? 14) * 0.92)
                  .toDouble(),
          height: 1.2,
        );

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 1, vertical: 1),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: borderRadius,
          onTap: () => onCopy(code),
          child: Ink(
            padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
            decoration: BoxDecoration(
              color: theme.colorScheme.surfaceContainerHighest.withValues(
                alpha: 0.72,
              ),
              borderRadius: borderRadius,
              border: Border.all(
                color: theme.colorScheme.outlineVariant.withValues(alpha: 0.45),
                width: 0.8,
              ),
            ),
            child: Text(code, style: codeStyle),
          ),
        ),
      ),
    );
  }
}

class OmnibotCodeBlockBuilder extends MarkdownElementBuilder {
  OmnibotCodeBlockBuilder({required this.onCopy});

  final OmnibotCodeCopyCallback onCopy;

  @override
  Widget visitElementAfterWithContext(
    BuildContext context,
    md.Element element,
    TextStyle? preferredStyle,
    TextStyle? parentStyle,
  ) {
    final code = _normalizedCodeText(element.textContent);
    final canCopy = code.trim().isNotEmpty;
    final theme = Theme.of(context);
    final borderRadius = BorderRadius.circular(14);
    final codeStyle = (preferredStyle ?? parentStyle ?? const TextStyle())
        .copyWith(
          fontFamily: 'monospace',
          backgroundColor: Colors.transparent,
          color: theme.colorScheme.onSurfaceVariant,
          fontSize:
              ((preferredStyle?.fontSize ?? parentStyle?.fontSize ?? 14) * 0.92)
                  .toDouble(),
          height: 1.45,
        );

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: borderRadius,
          onTap: canCopy ? () => onCopy(code) : null,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
            child: ChatDrawerGestureGuard(
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Text(code, style: codeStyle, softWrap: false),
              ),
            ),
          ),
        ),
      ),
    );
  }

  String _normalizedCodeText(String value) {
    if (value.endsWith('\n') && value.length > 1) {
      return value.substring(0, value.length - 1);
    }
    return value;
  }
}

class OmnibotInlineMathBuilder extends MarkdownElementBuilder {
  OmnibotInlineMathBuilder({required this.baseStyle});

  final TextStyle baseStyle;

  @override
  Widget visitElementAfterWithContext(
    BuildContext context,
    md.Element element,
    TextStyle? preferredStyle,
    TextStyle? parentStyle,
  ) {
    final expression = element.textContent.trim();
    if (expression.isEmpty) {
      return const SizedBox.shrink();
    }
    final style = (preferredStyle ?? parentStyle ?? baseStyle).copyWith(
      color: Theme.of(context).colorScheme.onSurface,
      height: 1.4,
    );
    return Text.rich(
      WidgetSpan(
        alignment: PlaceholderAlignment.middle,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 1),
          child: LayoutBuilder(
            builder: (context, constraints) {
              final maxWidth = _resolveMathMaxWidth(
                context,
                constraints,
                fallbackScreenRatio: 0.72,
              );
              return ConstrainedBox(
                constraints: BoxConstraints(maxWidth: maxWidth),
                child: FittedBox(
                  fit: BoxFit.scaleDown,
                  alignment: Alignment.centerLeft,
                  child: Math.tex(
                    expression,
                    mathStyle: MathStyle.text,
                    textStyle: style,
                    onErrorFallback:
                        (error) => Text('\$$expression\$', style: style),
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}

class OmnibotBlockMathBuilder extends MarkdownElementBuilder {
  OmnibotBlockMathBuilder({required this.baseStyle});

  final TextStyle baseStyle;

  @override
  bool isBlockElement() => true;

  @override
  Widget visitElementAfterWithContext(
    BuildContext context,
    md.Element element,
    TextStyle? preferredStyle,
    TextStyle? parentStyle,
  ) {
    final expression =
        (element.attributes[OmnibotMathBlockSyntax.expressionAttribute] ??
                element.textContent)
            .trim();
    if (expression.isEmpty) {
      return const SizedBox.shrink();
    }
    final style = (preferredStyle ?? parentStyle ?? baseStyle).copyWith(
      color: Theme.of(context).colorScheme.onSurface,
      height: 1.4,
    );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: ChatDrawerGestureGuard(
        child: SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Math.tex(
            expression,
            mathStyle: MathStyle.display,
            textStyle: style,
            onErrorFallback:
                (error) => Text('\$\$$expression\$\$', style: style),
          ),
        ),
      ),
    );
  }
}

class OmnibotInlineLinkBuilder extends MarkdownElementBuilder {
  OmnibotInlineLinkBuilder({
    this.inlineResourcePlainStyle = false,
    this.onResourceOpen,
  });

  final bool inlineResourcePlainStyle;
  final OmnibotResourceOpenCallback? onResourceOpen;

  @override
  Widget visitElementAfterWithContext(
    BuildContext context,
    md.Element element,
    TextStyle? preferredStyle,
    TextStyle? parentStyle,
  ) {
    final href = element.attributes['href'];
    final metadata =
        href == null ? null : OmnibotResourceService.resolveUri(href);
    if (metadata == null) {
      return Text.rich(
        WidgetSpan(
          alignment: PlaceholderAlignment.middle,
          child: InkWell(
            onTap:
                href == null
                    ? null
                    : () =>
                        _handleMarkdownLinkTap(context, href, onResourceOpen),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Text(
                element.textContent,
                style: preferredStyle?.copyWith(
                  color: Theme.of(context).colorScheme.primary,
                ),
              ),
            ),
          ),
        ),
      );
    }
    return Text.rich(
      WidgetSpan(
        alignment: PlaceholderAlignment.middle,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: OmnibotInlineResourceEmbed(
            metadata: metadata,
            plainStyle: inlineResourcePlainStyle,
            onOpen: onResourceOpen,
          ),
        ),
      ),
    );
  }
}

Future<void> _handleMarkdownLinkTap(
  BuildContext context,
  String href,
  OmnibotResourceOpenCallback? onResourceOpen,
) async {
  if (href.startsWith('omnibot://') && onResourceOpen != null) {
    await OmnibotResourceService.ensureWorkspacePathsLoaded();
    if (!context.mounted) return;
    final metadata = OmnibotResourceService.resolveUri(href);
    if (metadata != null) {
      await onResourceOpen(context, metadata);
      return;
    }
  }
  await OmnibotResourceService.handleLinkTap(href);
}

class OmnibotTrailingInlineSyntax extends md.InlineSyntax {
  OmnibotTrailingInlineSyntax() : super(_pattern);

  static const String _pattern = r'\[\[omnibot-trailing-inline\]\]';

  @override
  bool onMatch(md.InlineParser parser, Match match) {
    parser.addNode(md.Element.empty('omnibot-trailing-inline'));
    return true;
  }
}

class OmnibotTrailingInlineBuilder extends MarkdownElementBuilder {
  OmnibotTrailingInlineBuilder({required this.child});

  final Widget child;

  @override
  Widget visitElementAfterWithContext(
    BuildContext context,
    md.Element element,
    TextStyle? preferredStyle,
    TextStyle? parentStyle,
  ) {
    return Text.rich(
      WidgetSpan(alignment: PlaceholderAlignment.middle, child: child),
    );
  }
}

double _resolveMathMaxWidth(
  BuildContext context,
  BoxConstraints constraints, {
  double fallbackScreenRatio = 1.0,
}) {
  if (constraints.maxWidth.isFinite && constraints.maxWidth > 0) {
    return constraints.maxWidth;
  }
  final screenWidth = MediaQuery.maybeOf(context)?.size.width ?? 360;
  return screenWidth * fallbackScreenRatio;
}

String _linkifyBareOmnibotUris(String input) {
  final buffer = StringBuffer();
  final lines = input.split('\n');
  for (var i = 0; i < lines.length; i++) {
    final line = lines[i];
    final trimmed = line.trim();
    if (trimmed.startsWith('omnibot://') &&
        !trimmed.contains(' ') &&
        !trimmed.contains('[') &&
        !trimmed.contains(']')) {
      final parsed = Uri.tryParse(trimmed);
      final label =
          parsed?.pathSegments.isNotEmpty == true
              ? parsed!.pathSegments.last
              : (LegacyTextLocalizer.isEnglish ? 'Resource' : '资源');
      buffer.write('[$label]($trimmed)');
    } else {
      buffer.write(line);
    }
    if (i != lines.length - 1) {
      buffer.write('\n');
    }
  }
  return buffer.toString();
}

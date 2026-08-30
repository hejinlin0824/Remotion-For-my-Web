import { interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { COLORS, EXIT_F } from "../../engine/constants";
import { estimateLineCount, fitScale } from "../../engine/fit";
import type { MethodItem } from "../../engine/types";
import { CardShell, Row } from "./CardShell";

// v0.3 列表高度自适应常量（与渲染样式一一对应，改动须同步）
const TITLE_FONT = 34, TITLE_MT = 14;
const ROW_TEXT_FONT = 32, ROW_LABEL_FONT = 26, ROW_MT = 22;
const CARD_PAD_X = 48, CARD_PAD_Y = 40, CARD_BORDER = 2;
const CHIP_FONT = 26, CHIP_PAD_Y = 4, CHIP_MB = 26;
const ITEM_BORDER_X = 8, ITEM_PADL = 20, ROW_GAP_X = 18, LABEL_PAD_X = 14, LABEL_PAD_Y = 2;
const LIST_FLOOR = 0.55;   // 列表缩放下限：缩到 0.55 仍放不下 → 保持 0.55 照常渲染（QA 审帧兜底）
const EST_LH = 1.4;        // 估高行高系数（正文显式 1.4；标题浏览器 normal≈1.32，偏高估→偏保守）

/**
 * v0.3 列表高度自适应：估算 Σ(每步估行数×行高 + 间隙) 与可用高度预算（卡片主区高 −
 * CardShell padding/border − chip 及其下边距）比较，超出 → 整列表 fontSize/margin 按
 * budget/needed 等比缩小（下限 0.55），字号缩小时估行数同步复算（收敛式）；
 * 未超预算恒返回 1（golden 零漂移前提）。纯函数、确定性。
 */
export const listFitScale = (items: MethodItem[], availWidth: number, availHeight: number): number => {
  const budgetY = availHeight - CARD_BORDER * 2 - CARD_PAD_Y * 2 - (CHIP_FONT * EST_LH + CHIP_PAD_Y * 2) - CHIP_MB;
  const textWidth = availWidth - CARD_BORDER * 2 - CARD_PAD_X * 2 - ITEM_BORDER_X - ITEM_PADL;
  const labelW = 2 /* 套路 */ * ROW_LABEL_FONT + LABEL_PAD_X * 2;
  const trickWidth = textWidth - labelW - ROW_GAP_X;
  return fitScale((s) => items.reduce((h, item) => {
    const titleH = estimateLineCount(item.step, TITLE_FONT * s, textWidth) * TITLE_FONT * s * EST_LH;
    const rowH = Math.max(
      ROW_LABEL_FONT * s * EST_LH + LABEL_PAD_Y * 2,
      estimateLineCount(item.trick, ROW_TEXT_FONT * s, trickWidth) * ROW_TEXT_FONT * s * EST_LH);
    return h + TITLE_MT * s + titleH + ROW_MT * s + rowH;
  }, 0), budgetY, LIST_FLOOR);
};

const Item: React.FC<{ item: MethodItem; index: number; active: boolean; localStart: number;
  scale: number }> = ({ item, index, active, localStart, scale }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = spring({ frame: f - localStart, fps, config: { damping: 15, stiffness: 120 }, durationInFrames: 14 });
  if (s <= 0) return null;
  return (
    <div style={{ opacity: active ? 1 : 0.6 * s, borderLeft: active ? `8px solid ${COLORS.hl}` : "8px solid transparent",
      paddingLeft: 20, transform: `translateY(${(1 - s) * 24}px)` }}>
      <div style={{ fontSize: TITLE_FONT * scale, fontWeight: 700, color: active ? COLORS.hl : COLORS.text, marginTop: TITLE_MT * scale }}>
        {index + 1}. {item.step}
      </div>
      <Row label="套路" text={item.trick} color={COLORS.sub} scale={scale} />
    </div>
  );
};

export const GeneralList: React.FC<{ items: MethodItem[]; activeIndex: number;
  starts: number[]; span: number; availWidth?: number; availHeight?: number }> =
({ items, activeIndex, starts, span, availWidth = 1140, availHeight = 860 }) => {
  const f = useCurrentFrame();
  const exit = interpolate(f, [span - EXIT_F, span], [1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const scale = listFitScale(items, availWidth, availHeight);
  return (
    <div style={{ opacity: exit }}>
      <CardShell chip="通法总结" accent={COLORS.hl}>
        {items.map((item, i) => (
          <Item key={i} item={item} index={i} active={i === activeIndex} localStart={starts[i]} scale={scale} />
        ))}
      </CardShell>
    </div>
  );
};

import { spring, useCurrentFrame, useVideoConfig } from "remotion";
import { COLORS } from "../../engine/constants";
import { estimateLineCount, estimateTextWidth, fitScale } from "../../engine/fit";
import type { Pitfall } from "../../engine/types";
import { CardShell } from "./CardShell";
import { SceneCard } from "./anim";

// v0.3 列表高度自适应常量（与渲染样式一一对应，改动须同步）
const CLAIM_FONT = 38, CHECK_FONT = 40, ROW_MT = 20, ROW_GAP_X = 18;
const CARD_PAD_X = 48, CARD_PAD_Y = 40, CARD_BORDER = 2;
const CHIP_FONT = 26, CHIP_PAD_Y = 4, CHIP_MB = 26;
const LIST_FLOOR = 0.55;
const EST_LH = 1.4;

/**
 * v0.3 检查清单高度自适应：估算 Σ(行数×行高 + 行距) 与可用高度预算比较，
 * 超出 → 整列表 fontSize/margin 等比缩小（下限 0.55），保证全部条目入画；
 * 未超预算恒返回 1。纯函数、确定性。
 */
export const checklistFitScale = (pitfalls: Pitfall[], availWidth: number, availHeight: number): number => {
  const budgetY = availHeight - CARD_BORDER * 2 - CARD_PAD_Y * 2 - (CHIP_FONT * EST_LH + CHIP_PAD_Y * 2) - CHIP_MB;
  const claimWidth = availWidth - CARD_BORDER * 2 - CARD_PAD_X * 2
    - estimateTextWidth("✓", CHECK_FONT) - ROW_GAP_X;
  return fitScale((s) => pitfalls.reduce((h, p) => {
    const rowH = Math.max(CHECK_FONT * s * EST_LH,
      estimateLineCount(p.claim, CLAIM_FONT * s, claimWidth) * CLAIM_FONT * s * EST_LH);
    return h + ROW_MT * s + rowH;
  }, 0), budgetY, LIST_FLOOR);
};

export const ChecklistCard: React.FC<{ pitfalls: Pitfall[]; durFrames: number;
  availWidth?: number; availHeight?: number }> =
({ pitfalls, durFrames, availWidth = 1140, availHeight = 860 }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const scale = checklistFitScale(pitfalls, availWidth, availHeight);
  return (
    <SceneCard durFrames={durFrames}>
      <CardShell chip="检查清单" accent={COLORS.ok}>
        {pitfalls.map((p, i) => {
          const s = spring({ frame: f - 10 - i * 8, fps, config: { damping: 15, stiffness: 130 }, durationInFrames: 12 });
          return (
            <div key={i} style={{ display: "flex", gap: 18, alignItems: "center", marginTop: 20 * scale, opacity: s }}>
              <span style={{ fontSize: 40 * scale, color: COLORS.ok, fontWeight: 800 }}>✓</span>
              <span style={{ fontSize: 38 * scale, color: COLORS.text }}>{p.claim}</span>
            </div>
          );
        })}
      </CardShell>
    </SceneCard>
  );
};

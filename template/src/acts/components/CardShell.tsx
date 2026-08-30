import type { ReactNode } from "react";
import { COLORS } from "../../engine/constants";

export const CardShell: React.FC<{ chip: string; accent?: string; children: ReactNode }> =
({ chip, accent = COLORS.accent, children }) => (
  <div style={{ backgroundColor: COLORS.card, border: `2px solid ${COLORS.cardBorder}`,
    borderRadius: 24, padding: "40px 48px", width: "100%", boxShadow: "0 12px 40px rgba(0,0,0,0.45)" }}>
    <div style={{ display: "inline-block", fontSize: 26, fontWeight: 700, color: COLORS.ink,
      backgroundColor: accent, borderRadius: 10, padding: "4px 18px", marginBottom: 26 }}>{chip}</div>
    {children}
  </div>
);

export const Row: React.FC<{ label: string; text: string; color: string; scale?: number }> =
({ label, text, color, scale = 1 }) => (
  <div style={{ display: "flex", gap: 18, alignItems: "baseline", marginTop: 22 * scale }}>
    <span style={{ flexShrink: 0, fontSize: 26 * scale, fontWeight: 700, color: COLORS.ink,
      backgroundColor: color, borderRadius: 8, padding: "2px 14px" }}>{label}</span>
    <span style={{ fontSize: 32 * scale, color: COLORS.text, lineHeight: 1.4 }}>{text}</span>
  </div>
);

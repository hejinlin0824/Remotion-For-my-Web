// src/acts/LectureVideo.tsx — 占位，仅验证渲染链路
import "katex/dist/katex.min.css";
import { AbsoluteFill } from "remotion";
import { COLORS, FONT_FAMILY } from "../engine/constants";

export const LectureVideo: React.FC<{ aspect: "16:9" | "9:16" }> = ({ aspect }) => (
  <AbsoluteFill style={{ backgroundColor: COLORS.bg, color: COLORS.text, fontFamily: FONT_FAMILY,
    justifyContent: "center", alignItems: "center", fontSize: 60 }}>
    scaffold ok {aspect}
  </AbsoluteFill>
);

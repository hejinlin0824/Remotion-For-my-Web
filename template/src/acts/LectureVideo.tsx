// 临时挂载：验证幕1/幕5固定幕曲线 + 音频占位（Task 9 重写为 timeline 驱动）
import { AbsoluteFill, Sequence, staticFile, Audio } from "remotion";
import { COLORS, FONT_FAMILY } from "../engine/constants";
import { Act1Intro } from "./Act1Intro";
import { Act5Outro } from "./Act5Outro";

export const LectureVideo: React.FC<{ aspect: "16:9" | "9:16" }> = ({ aspect }) => (
  <AbsoluteFill style={{ backgroundColor: COLORS.bg, color: COLORS.text, fontFamily: FONT_FAMILY }}>
    <Sequence durationInFrames={120}>
      <Act1Intro durFrames={120} />
      <Audio src={staticFile("audio/fixed/act1.wav")} />
    </Sequence>
    <Sequence from={130} durationInFrames={180}>
      <Act5Outro durFrames={180} />
      <Audio src={staticFile("audio/fixed/act5.wav")} />
    </Sequence>
  </AbsoluteFill>
);

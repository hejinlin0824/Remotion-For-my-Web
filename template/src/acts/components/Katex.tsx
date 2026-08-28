import katex from "katex";
import { useMemo } from "react";
import { COLORS } from "../../engine/constants";

export const Katex: React.FC<{ tex: string; fontSize?: number; color?: string }> =
({ tex, fontSize = 44, color = COLORS.text }) => {
  const html = useMemo(() =>
    katex.renderToString(tex, { throwOnError: false, output: "html" }), [tex]);
  return (
    <div style={{ fontSize, color, lineHeight: 1.35, maxWidth: "100%", overflow: "hidden" }}
      dangerouslySetInnerHTML={{ __html: html }} />
  );
};

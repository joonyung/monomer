import { useState } from "react";
import type { CellView } from "@/features/table-widget/contract/abi";

export function TableGraphicCell({ view }: { view: CellView }) {
  const [failed, setFailed] = useState(false);
  return view.graphic && !failed
    ? <img className="dashboard-table-graphic" src={`data:image/svg+xml;charset=utf-8,${encodeURIComponent(view.graphic.svg)}`} alt={view.text} onError={() => setFailed(true)} />
    : <span>{view.text}</span>;
}

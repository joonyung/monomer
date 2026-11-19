import { create } from "zustand";

export interface ConnectorCreationNotice {
  connectorId: string;
  connectorName: string;
}

interface ConnectorCreationNoticeState {
  notice: ConnectorCreationNotice | null;
  announce: (notice: ConnectorCreationNotice) => void;
  dismiss: () => void;
}

// Carries the one-time "saved" confirmation from Connectors to the Catalog page it navigates to.
export const useConnectorCreationNotice = create<ConnectorCreationNoticeState>((set) => ({
  notice: null,
  announce: (notice) => set({ notice }),
  dismiss: () => set({ notice: null }),
}));

export function connectorCreationNoticeMessage(connectorName: string, threeDepth: boolean): string {
  return threeDepth
    ? `${connectorName} saved. Choose its catalogs, then the tables to load — listing the catalogs is the first connection to the warehouse.`
    : `${connectorName} saved. Choose the tables to load — listing the schemas is the first connection to the warehouse.`;
}

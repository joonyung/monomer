import { DashboardApiError, type DashboardResult } from "@/api/dashboard";

export async function readDashboardResultWithAccess(load: () => Promise<DashboardResult>): Promise<DashboardResult | null> {
  try {
    return await load();
  } catch (error) {
    if (error instanceof DashboardApiError && (error.status === 401 || error.status === 403)) return null;
    throw error;
  }
}

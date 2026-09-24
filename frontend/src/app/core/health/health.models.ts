export type HealthStatus = 'UP' | 'DOWN';

export interface HealthResponse {
  status: HealthStatus;
  service: string;
  timestamp: string;
}

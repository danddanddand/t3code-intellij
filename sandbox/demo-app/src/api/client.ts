export interface ApiResult<T> {
  ok: boolean;
  data?: T;
  error?: string;
}

export async function post<T>(path: string, body: unknown): Promise<ApiResult<T>> {
  const response = await fetch(`/api${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    return { ok: false, error: `Request failed with status ${response.status}` };
  }
  return { ok: true, data: (await response.json()) as T };
}

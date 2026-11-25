import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { AuthProvider } from "../src/Auth";
import { CompanyProvider, useCompany } from "../src/Company";
import { UnsavedProvider } from "../src/ui/Unsaved";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

function Probe() {
  const { active, companies } = useCompany();
  return <div data-testid="probe">{active?.name ?? "none"}:{companies.length}</div>;
}

it("bootstraps CompanyProvider directly from /api/me without an extra /api/companies request", async () => {
  const fetcher = vi.fn().mockImplementation((url: string) => {
    if (url === "/api/me") {
      return Promise.resolve(new Response(JSON.stringify({
        user: "ana",
        activeId: 2,
        companies: [
          { id: 1, name: "Alpha", off: "", daily: "8", crea: "" },
          { id: 2, name: "Beta", off: "", daily: "8", crea: "" },
        ],
      }), { status: 200 }));
    }
    return Promise.reject(new Error("unexpected fetch: " + url));
  });
  vi.stubGlobal("fetch", fetcher);

  const router = createMemoryRouter([{
    path: "*",
    element: (
      <AuthProvider>
        <UnsavedProvider>
          <CompanyProvider>
            <Probe />
          </CompanyProvider>
        </UnsavedProvider>
      </AuthProvider>
    ),
  }]);
  render(<RouterProvider router={router} />);

  const el = await screen.findByTestId("probe");
  expect(el.textContent).toBe("Beta:2");
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(fetcher.mock.calls[0][0]).toBe("/api/me");
});

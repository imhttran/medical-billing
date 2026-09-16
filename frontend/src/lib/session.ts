"use client";

import { useCallback, useEffect, useState } from "react";
import { callApi } from "./api";

export type SessionUser = {
  id: number;
  email: string;
  role: string;
  emailVerified: boolean;
  mustChangePassword?: boolean;
  hasProfile?: boolean;
};

/**
 * The bootstrap every signed-in billing page needs: no token means back to
 * login, a temporary password means /change-password first, no profile means
 * /profile first. The backend enforces all three, so these redirects are for the
 * user's benefit rather than the security boundary.
 *
 * `withToken` reads the stored token at call time rather than closing over one
 * from render, so a JWT renewed by a previous response is the one used next.
 */
export function useSession() {
  const [me, setMe] = useState<SessionUser | null>(null);

  useEffect(() => {
    void (async () => {
      const stored = localStorage.getItem("auth_token");
      if (!stored) {
        window.location.href = "/";
        return;
      }
      // notify=false: this is the page's own load, so a failure redirects
      // instead of alerting.
      const result = await callApi<{ user: SessionUser }>(
        stored,
        "/api/me",
        "GET",
        undefined,
        false,
      );
      if (!result) {
        localStorage.removeItem("auth_token");
        window.location.href = "/";
        return;
      }
      if (result.user.mustChangePassword) {
        window.location.href = "/change-password";
        return;
      }
      if (!result.user.hasProfile) {
        window.location.href = "/profile";
        return;
      }
      setMe(result.user);
    })();
  }, []);

  const withToken = useCallback((run: (authToken: string) => Promise<void>) => {
    const authToken = localStorage.getItem("auth_token");
    if (authToken) void run(authToken);
  }, []);

  return { me, withToken };
}

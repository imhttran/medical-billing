"use client";

import { useCallback, useEffect, useState, type MouseEvent } from "react";
import { getJson } from "@/lib/api";
import { useSession } from "@/lib/session";
import { PageHeader } from "@/components/PageHeader";
import { PageFooter } from "@/components/PageFooter";
import { PageTitle } from "@/components/PageTitle";

type AuditEvent = {
  id: number;
  organizationId: number | null;
  userId: number | null;
  userEmail: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  timestamp: string;
  metadata: Record<string, unknown>;
};

/** The actions this deployment records, for the filter. The list is the map. */
const ACTIONS = [
  { value: "", label: "Everything" },
  { value: "CLAIM_SUBMITTED", label: "Claim submitted" },
  { value: "CLAIM_RESUBMITTED", label: "Claim resubmitted" },
  { value: "PATIENT_PAYMENT_RECORDED", label: "Patient payment recorded" },
  { value: "WORK_ITEM_ASSIGNED", label: "Work item assigned" },
  { value: "WORK_ITEM_RESOLVED", label: "Work item resolved" },
  { value: "ROLE_ASSIGNED", label: "Role assigned" },
  { value: "DEMO_RESET", label: "Demo data reset" },
];

const LIMITS = ["50", "100", "250"];

const LABELS: Record<string, string> = Object.fromEntries(
  ACTIONS.filter((option) => option.value).map((option) => [
    option.value,
    option.label,
  ]),
);

/** The instant, to the second: an audit row is read for when, not for a zone. */
const moment = (timestamp: string) =>
  timestamp.replace("T", " ").replace("Z", "").slice(0, 19);

/** `roleCode=BILLER targetUserId=7` — ids and codes, which is what is stored. */
const details = (metadata: Record<string, unknown>) => {
  const entries = Object.entries(metadata ?? {});
  if (entries.length === 0) return "—";
  return entries.map(([key, value]) => `${key}=${String(value)}`).join(" ");
};

/**
 * The audit trail. Read-only: the endpoint behind it has no write side, because
 * a trail someone can edit is not evidence of anything.
 *
 * The list is whatever the caller's grants cover, so a role without AUDIT_VIEW
 * gets an empty table rather than an explanation. The nav no longer offers this
 * page to them, but a typed URL still lands on one.
 */
export default function AuditPage() {
  const { me, withToken } = useSession();

  const [events, setEvents] = useState<AuditEvent[] | null>(null);
  const [action, setAction] = useState("");
  const [limit, setLimit] = useState("100");
  const [failed, setFailed] = useState(false);

  const load = useCallback(
    async (authToken: string) => {
      const query = new URLSearchParams({ limit });
      if (action) query.set("action", action);
      const { ok, data } = await getJson<{ auditEvents: AuditEvent[] }>(
        authToken,
        `/api/audit-events?${query.toString()}`,
      );
      if (ok) setEvents(data.auditEvents);
      setFailed(!ok);
    },
    [action, limit],
  );

  useEffect(() => {
    if (!me) return;
    withToken(load);
  }, [me, load, withToken]);

  const logout = (event: MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault();
    localStorage.removeItem("auth_token");
    window.location.href = "/";
  };

  return (
    <div className="dashboard-container wide">
      <PageTitle title="Audit trail | Medical Billing" />
      <PageHeader
        title="Audit trail"
        subtitle="What was done in your practices, in the order it happened"
        current="/audit"
        permissions={me?.permissions}
      >
        <a className="logout-link" href="/" onClick={logout}>
          Logout
        </a>
      </PageHeader>

      <div className="dashboard-card">
        <div className="user-list-section">
          {failed ? <p role="alert">Failed to load the audit trail.</p> : null}

          <div className="input-group">
            <label htmlFor="action">Action</label>
            <select
              id="action"
              name="action"
              value={action}
              onChange={(event) => setAction(event.target.value)}
            >
              {ACTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>

          <div className="input-group">
            <label htmlFor="limit">Show</label>
            <select
              id="limit"
              name="limit"
              value={limit}
              onChange={(event) => setLimit(event.target.value)}
            >
              {LIMITS.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </div>

          <div className="table-scroll">
            <table className="user-table">
              <thead>
                <tr>
                  <th>When</th>
                  <th>Who</th>
                  <th>Action</th>
                  <th>Record</th>
                  <th>Details</th>
                </tr>
              </thead>
              <tbody>
                {events === null ? (
                  <tr>
                    <td colSpan={5}>Loading…</td>
                  </tr>
                ) : events.length === 0 ? (
                  <tr>
                    <td colSpan={5}>
                      {action
                        ? "Nothing of that kind here."
                        : "Nothing recorded in the practices you can read."}
                    </td>
                  </tr>
                ) : (
                  events.map((event) => (
                    <tr key={event.id}>
                      <td>{moment(event.timestamp)}</td>
                      <td>{event.userEmail ?? "—"}</td>
                      <td>{LABELS[event.action] ?? event.action}</td>
                      <td>
                        {event.entityType === "Claim" && event.entityId ? (
                          <a href={`/claims/${event.entityId}`}>
                            {event.entityType} #{event.entityId}
                          </a>
                        ) : (
                          `${event.entityType} #${event.entityId ?? "—"}`
                        )}
                      </td>
                      <td>
                        <code>{details(event.metadata)}</code>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <PageFooter />
    </div>
  );
}

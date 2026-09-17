import type { ReactNode } from "react";
import { Logo } from "@/components/Logo";

// Every signed-in page offers the same destinations, and hand-writing that list
// per page is how they drifted apart — from some pages you couldn't reach the
// dashboard or the audit trail at all. So the list lives here, once.
//
// A destination that reads through a billing permission names it. The list
// endpoints scope rows by that permission and answer an empty list rather than a
// refusal, so a link the caller holds nothing for opens on an empty table with
// nothing explaining why. Dashboard is the landing page and reads nothing, so it
// carries no permission and is never hidden.
const DESTINATIONS = [
  { href: "/claims", label: "Claims", permission: "CLAIM_VIEW" },
  { href: "/patients", label: "Patients", permission: "PATIENT_VIEW" },
  { href: "/work-queue", label: "Work queue", permission: "WORK_QUEUE_VIEW" },
  { href: "/dashboard", label: "Dashboard" },
  { href: "/audit", label: "Audit trail", permission: "AUDIT_VIEW" },
];

/**
 * The brand mark leads the title, the optional subtitle sits under it, the
 * navigation sits beside them as a <nav> landmark, and children (e.g. a logout
 * link) sit on the right.
 *
 * The mark sits inside the <h1>, on the title's own line box. Centered against
 * the title and subtitle together it floated between the two, and it has to
 * ride the heading rather than the block to sit on the title. It's aria-hidden
 * so the heading's accessible name stays just the page, since a mark announced
 * on every page is noise.
 *
 * `current` is the section the page belongs to, so the link for the page you are
 * already on is marked with aria-current rather than reading as somewhere to go.
 * Both records under /claims and /patients keep the section, not the exact route.
 *
 * `permissions` comes from /api/me. It is undefined until the session resolves,
 * and until then every destination shows — hiding links that are about to appear
 * reads worse than a moment of a link that is about to go.
 */
export function PageHeader({
  title,
  subtitle,
  current,
  permissions,
  children,
}: {
  title: string;
  subtitle?: ReactNode;
  current: string;
  permissions: readonly string[] | undefined;
  children?: ReactNode;
}) {
  return (
    <header className="page-header">
      <div className="page-header-title">
        <h1>
          <span aria-hidden="true">
            <Logo size={28} />
          </span>
          {title}
        </h1>
        {subtitle ? <p>{subtitle}</p> : null}
      </div>
      <nav className="page-nav">
        {DESTINATIONS.filter(
          ({ permission }) =>
            !permission || !permissions || permissions.includes(permission),
        ).map(({ href, label }) => (
          <a
            key={href}
            href={href}
            aria-current={href === current ? "page" : undefined}
          >
            {label}
          </a>
        ))}
      </nav>
      {children}
    </header>
  );
}

import { Logo } from "@/components/Logo";

/**
 * The brand mark above an auth card. Every one of the auth screens shows it
 * above the card rather than inside it, so the size and the wrapper live here
 * instead of in seven page files — which is how six of them lost it.
 *
 * It's aria-hidden because the card's own <h1> already names the screen, the
 * same rule the app's page header follows. The class name is the one login
 * used before this component existed, and it still matches the surrounding
 * .login-container and .login-form.
 */
export function AuthMark() {
  return (
    <div className="login-logo" aria-hidden="true">
      <Logo size={48} />
    </div>
  );
}

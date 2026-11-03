<?php
declare(strict_types=1);
/** Install OUTSIDE the public document root and replace this fail-closed adapter.
 * Return a stable account ID string ONLY after existing Nyama server authorization verifies:
 * - permanent token hash, installation ID, revocation and ownership together;
 * - verified email, active/non-suspended account and current viewing entitlement;
 * - existing VIP/hourly/paid/trial rules (no new local subscription rules).
 * Do not accept an ID or token merely because its format is valid.
 * Use the deployed server's authoritative auth helper/prepared queries. No schema is assumed here.
 * Do not call login with a customer's password or write/renew/clear any activation.
 */
return static function (string $bearer, string $activationId): ?string {
    return null;
};

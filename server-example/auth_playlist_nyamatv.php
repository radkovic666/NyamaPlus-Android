<?php
require_once __DIR__ . '/config.php';

error_reporting(E_ALL);
ini_set('display_errors', 0);

// ============ CONFIG ============
$playlistFile = __DIR__ . "/playlist.m3u";
$paymentStream = __DIR__ . "/nopaid.m3u";
// =================================

// Read credentials
$reqUser = $_GET['username'] ?? '';
$reqPass = $_GET['password'] ?? '';

if ($reqUser === '' || $reqPass === '') {
    http_response_code(400);
    exit("Missing credentials");
}

// IMPORTANT: Xtream passwords are ALREADY MD5
$passHash = $reqPass;

// Client info
$userAgent = $_SERVER['HTTP_USER_AGENT'] ?? 'unknown';
$ip = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';
$accountKey = md5($reqUser . ':' . $passHash);

// Nyama TV sends a stable per-installation ID in this header while keeping a
// normal VLC User-Agent. Existing Televizo/VLC/Kodi clients still fall back to
// the previous User-Agent-based fingerprint behavior.
$nyamaDeviceId = trim($_SERVER['HTTP_X_NYAMA_DEVICE_ID'] ?? '');
if ($nyamaDeviceId !== '' && preg_match('/^[A-Za-z0-9._:-]{8,128}$/', $nyamaDeviceId)) {
    $fingerprint = md5($accountKey . '|device|' . $nyamaDeviceId);
} else {
    $fingerprint = md5($accountKey . '|ua|' . $userAgent);
}

// ================= AUTH =================
$stmt = $pdo->prepare("
    SELECT 
        u.id AS user_id,
        u.status,
        u.paid_until,
        u.cluster_id,
        u.subscription_type,
        u.max_devices,
        x.is_active
    FROM xtream_codes x
    JOIN users u ON u.id = x.user_id
    WHERE x.xtream_username = :u
      AND x.xtream_password = :p
    LIMIT 1
");
$stmt->execute([
    ':u' => $reqUser,
    ':p' => $passHash
]);

$user = $stmt->fetch(PDO::FETCH_ASSOC);

if (!$user) {
    http_response_code(403);
    exit("Invalid username or password");
}

if ($user['status'] !== 'active' || !$user['is_active']) {
    http_response_code(403);
    exit("Account disabled");
}

$userId = (int)$user['user_id'];
$clusterId = $user['cluster_id'];
$isVipUser = ($clusterId == 2);
$subscriptionType = $user['subscription_type'];
$maxDevices = $user['max_devices'] ?? 1;

// Check payment status - VIP users have unlimited access
if (!$isVipUser) {
    if ($user['paid_until'] !== null && strtotime($user['paid_until']) < time()) {
        // Account expired - serve payment stream if exists
        if (file_exists($paymentStream)) {
            header("Content-Type: application/vnd.apple.mpegurl");
            header("Cache-Control: no-cache, no-store, must-revalidate, max-age=0, private");
            header("Pragma: no-cache");
            header("Expires: Thu, 01 Jan 1970 00:00:00 GMT");
            readfile($paymentStream);
            exit;
        } else {
            http_response_code(403);
            exit("Account expired. Please scan QR code to renew.");
        }
    }
}

// ================= DEVICE BINDING (MULTI-DEVICE SUPPORT - FIXED) =================

// First, ensure device_slot column exists and has default values
try {
    $pdo->exec("ALTER TABLE device_bindings ADD COLUMN IF NOT EXISTS device_slot INT NOT NULL DEFAULT 1 AFTER user_id");
    $pdo->exec("ALTER TABLE device_bindings ADD UNIQUE INDEX IF NOT EXISTS unique_user_device_slot (user_id, device_slot)");
} catch (PDOException $e) {
    error_log("DB alter error: " . $e->getMessage());
}

// Fix constraints that prevent multiple devices per user
try {
    // Drop the old unique constraints (they are incompatible with multi‑device)
    $pdo->exec("ALTER TABLE device_bindings DROP INDEX IF EXISTS account_key");
    $pdo->exec("ALTER TABLE device_bindings DROP INDEX IF EXISTS unique_user_binding");
    // Add a regular index on account_key for lookups (if not exists)
    $pdo->exec("ALTER TABLE device_bindings ADD INDEX IF NOT EXISTS idx_account_key (account_key)");
    // Ensure the composite unique index exists (already tried, but do it again to be safe)
    $pdo->exec("ALTER TABLE device_bindings ADD UNIQUE INDEX IF NOT EXISTS unique_user_device_slot (user_id, device_slot)");
} catch (PDOException $e) {
    error_log("DB constraint fix error: " . $e->getMessage());
}

// Get all device bindings for this user
$stmt = $pdo->prepare("
    SELECT * FROM device_bindings 
    WHERE user_id = :uid 
    ORDER BY device_slot
");
$stmt->execute([':uid' => $userId]);
$bindings = $stmt->fetchAll(PDO::FETCH_ASSOC);

// Check if this device fingerprint already exists
$existingBinding = null;
foreach ($bindings as $binding) {
    if ($binding['device_fingerprint'] === $fingerprint) {
        $existingBinding = $binding;
        break;
    }
}

$currentDeviceCount = count($bindings);
$deviceSlotToUse = null;

// Log for debugging
error_log("User {$userId} - Type: {$subscriptionType}, Current devices: {$currentDeviceCount}/{$maxDevices}, Existing binding: " . ($existingBinding ? "Yes (slot {$existingBinding['device_slot']})" : "No"));

if ($existingBinding) {
    // Update existing device
    $deviceSlotToUse = $existingBinding['device_slot'];
    $updateStmt = $pdo->prepare("
        UPDATE device_bindings 
        SET last_seen = NOW(), ip_address = :ip, user_agent = :ua
        WHERE id = :bid
    ");
    $updateStmt->execute([
        ':ip' => $ip,
        ':ua' => $userAgent,
        ':bid' => $existingBinding['id']
    ]);
    
    error_log("User {$userId}: Updated existing device slot {$deviceSlotToUse}");
    
} else {
    // New device - need to add it
    if ($subscriptionType === 'subscription') {
        // Check if user has reached max devices
        if ($currentDeviceCount >= $maxDevices) {
            $errorMsg = "Maximum devices reached (" . $currentDeviceCount . "/" . $maxDevices . "). Please remove a device first.";
            error_log("Device binding failed for user {$userId}: " . $errorMsg);
            http_response_code(403);
            exit($errorMsg);
        }
        
        // Find next available device slot (1, 2, 3... up to maxDevices)
        $usedSlots = array_column($bindings, 'device_slot');
        $deviceSlotToUse = 1;
        while (in_array($deviceSlotToUse, $usedSlots) && $deviceSlotToUse <= $maxDevices) {
            $deviceSlotToUse++;
        }
        
        if ($deviceSlotToUse > $maxDevices) {
            $errorMsg = "No available device slots.";
            error_log("Device binding failed for user {$userId}: " . $errorMsg);
            http_response_code(403);
            exit($errorMsg);
        }
        
        // Add new device
        $insertStmt = $pdo->prepare("
            INSERT INTO device_bindings 
            (user_id, device_slot, account_key, device_fingerprint, user_agent, ip_address, created_at, last_seen) 
            VALUES (:uid, :slot, :akey, :fp, :ua, :ip, NOW(), NOW())
        ");
        $insertStmt->execute([
            ':uid' => $userId,
            ':slot' => $deviceSlotToUse,
            ':akey' => $accountKey,
            ':fp' => $fingerprint,
            ':ua' => $userAgent,
            ':ip' => $ip
        ]);
        
        error_log("User {$userId}: Added NEW device in slot {$deviceSlotToUse} (total: " . ($currentDeviceCount + 1) . "/{$maxDevices})");
        
    } else {
        // HOURLY USERS: Single device only
        if ($currentDeviceCount > 0) {
            $errorMsg = "Account already bound to another device. Hourly users can only use 1 device.";
            error_log("Device binding failed for hourly user {$userId}: " . $errorMsg);
            http_response_code(403);
            exit($errorMsg);
        }
        
        $deviceSlotToUse = 1;
        
        // Add first device
        $insertStmt = $pdo->prepare("
            INSERT INTO device_bindings 
            (user_id, device_slot, account_key, device_fingerprint, user_agent, ip_address, created_at, last_seen) 
            VALUES (:uid, 1, :akey, :fp, :ua, :ip, NOW(), NOW())
        ");
        $insertStmt->execute([
            ':uid' => $userId,
            ':akey' => $accountKey,
            ':fp' => $fingerprint,
            ':ua' => $userAgent,
            ':ip' => $ip
        ]);
        
        error_log("Hourly user {$userId}: Added first device");
    }
    
    // Log the new device binding
    $logStmt = $pdo->prepare("
        INSERT INTO device_binding_logs (user_id, device_slot, action, fingerprint, user_agent, ip_address) 
        VALUES (?, ?, 'device_bound', ?, ?, ?)
    ");
    $logStmt->execute([$userId, $deviceSlotToUse, $fingerprint, $userAgent, $ip]);
    
    // Update user's device_count
    $updateCountStmt = $pdo->prepare("UPDATE users SET device_count = (SELECT COUNT(*) FROM device_bindings WHERE user_id = ?) WHERE id = ?");
    $updateCountStmt->execute([$userId, $userId]);
}

// Double-check we have a device slot
if (!$deviceSlotToUse) {
    $deviceSlotToUse = 1;
    error_log("WARNING: deviceSlotToUse was null, defaulting to 1 for user {$userId}");
}

// ================= SERVE DYNAMIC PLAYLIST WITH HLS HEADERS =================
if (!file_exists($playlistFile)) {
    http_response_code(500);
    exit("Playlist missing");
}

// Read the original playlist
$originalContent = file_get_contents($playlistFile);

// Generate dynamic values for auto-refresh
$currentTime = time();
$mediaSequence = $currentTime;
$timestamp = date('Y-m-d H:i:s');

// Get current device count for display
$stmt = $pdo->prepare("SELECT COUNT(*) FROM device_bindings WHERE user_id = ?");
$stmt->execute([$userId]);
$deviceCount = $stmt->fetchColumn();

// Force no cache headers
header("Content-Type: application/x-mpegurl");
header("Cache-Control: no-cache, no-store, must-revalidate, max-age=0, private");
header("Pragma: no-cache");
header("Expires: Thu, 01 Jan 1970 00:00:00 GMT");

// Output playlist with HLS tags that force auto-refresh
echo "#EXTM3U\n";
echo "#EXT-X-VERSION:3\n";
echo "#EXT-X-TARGETDURATION:10\n";
echo "#EXT-X-MEDIA-SEQUENCE:" . $mediaSequence . "\n";
echo "#EXT-X-PLAYLIST-TYPE:EVENT\n";
echo "#EXT-X-ALLOW-CACHE:NO\n";
echo "# Playlist generated: " . $timestamp . "\n";
echo "# User: " . htmlspecialchars($reqUser) . "\n";
echo "# Account type: " . ($subscriptionType === 'subscription' ? 'Subscription' : 'Hourly') . "\n";
echo "# Devices: " . $deviceCount . "/" . ($subscriptionType === 'subscription' ? $maxDevices : 1) . "\n";
echo "# Device slot: " . $deviceSlotToUse . "\n";
echo "# Account expires: " . ($user['paid_until'] ?? 'Never') . "\n";
echo "# VIP status: " . ($isVipUser ? 'Yes' : 'No') . "\n\n";

// Parse and output the playlist content with timestamp parameters
$lines = explode("\n", $originalContent);
$inExtinf = false;
$lastExtinf = '';

foreach ($lines as $line) {
    $line = trim($line);
    if (empty($line)) continue;
    
    // Check if this is an EXTINF line
    if (strpos($line, '#EXTINF') === 0) {
        $lastExtinf = $line;
        $inExtinf = true;
        echo $line . "\n";
    } 
    // Check if this is a URL line (starts with http:// or https://)
    elseif (preg_match('/^(http|https):\/\//', $line)) {
        // Add timestamp and device info to URL to prevent caching
        if (strpos($line, '?') !== false) {
            $line .= "&_t=" . $currentTime . "&device_slot=" . $deviceSlotToUse;
        } else {
            $line .= "?_t=" . $currentTime . "&device_slot=" . $deviceSlotToUse;
        }
        echo $line . "\n";
        $inExtinf = false;
    }
    // Handle other comment lines
    elseif (strpos($line, '#') === 0 && $line !== '#EXTM3U') {
        echo $line . "\n";
    }
    // If we're not in EXTINF and not a URL, it might be a URL without http (relative path)
    elseif (!$inExtinf && !empty($line) && strpos($line, '#') !== 0) {
        // Add timestamp to relative URLs
        if (strpos($line, '?') !== false) {
            $line .= "&_t=" . $currentTime . "&device_slot=" . $deviceSlotToUse;
        } else {
            $line .= "?_t=" . $currentTime . "&device_slot=" . $deviceSlotToUse;
        }
        echo $line . "\n";
    }
}

exit;
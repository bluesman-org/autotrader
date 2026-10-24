/*
 * Autotrader's Web Push service worker. It is registered from the settings page and lives at the
 * application root so its scope covers the whole app. It shows what the server sends and focuses
 * the live page when the notification is clicked; it deliberately has no fetch handler, so the app
 * keeps its normal network path.
 */
self.addEventListener('push', function (event) {
    let data = {};
    try {
        data = event.data ? event.data.json() : {};
    } catch (error) {
        data = {title: 'Autotrader', body: event.data ? event.data.text() : ''};
    }
    const title = data.title || 'Autotrader';
    const options = {
        body: data.body || '',
        tag: data.tag || 'autotrader',
        data: {url: data.url || '/autotrader/ui/live'},
        icon: '/autotrader/icon.svg',
        badge: '/autotrader/icon.svg'
    };
    event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener('notificationclick', function (event) {
    event.notification.close();
    const data = event.notification.data || {};
    const url = data.url || '/autotrader/ui/live';
    event.waitUntil(self.clients.matchAll({type: 'window', includeUncontrolled: true})
        .then(function (clientList) {
            for (const client of clientList) {
                if ('focus' in client) {
                    if ('navigate' in client) {
                        client.navigate(url);
                    }
                    return client.focus();
                }
            }
            if (self.clients.openWindow) {
                return self.clients.openWindow(url);
            }
            return undefined;
        }));
});

/*
 * FCM and Firefox can rotate a subscription without the settings page being open. Register the new
 * one with the same server so the device keeps receiving alerts; if the session has expired the
 * POST answers 401, and the next settings visit can enable the device again.
 */
self.addEventListener('pushsubscriptionchange', function (event) {
    event.waitUntil((async function () {
        try {
            const keyResponse = await fetch('/autotrader/api/push/public-key', {
                credentials: 'same-origin'
            });
            if (!keyResponse.ok) {
                return;
            }
            const key = new Uint8Array(await keyResponse.arrayBuffer());
            const subscription = await self.registration.pushManager.subscribe({
                userVisibleOnly: true,
                applicationServerKey: key
            });
            await fetch('/autotrader/api/push/subscriptions', {
                method: 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(subscription)
            });
        } catch (error) {
            // A failed re-subscription must not break the worker; the settings page can re-enable.
        }
    })());
});


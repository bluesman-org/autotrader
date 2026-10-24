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

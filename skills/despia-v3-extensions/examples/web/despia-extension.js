import despia from 'despia-native';

// App helper, not an export from despia-native. Call only in the browser.
export async function callExtension(command) {
  const suffix = crypto.randomUUID().replace(/-/g, '');
  const callbackKey = 'nxCallback' + suffix;
  const watchKey = 'nxReply' + suffix;
  const url = new URL(command.replace(/\+/g, '%2B'));
  url.searchParams.set('callback', callbackKey);
  globalThis[callbackKey] = envelope => { globalThis[watchKey] = envelope; };
  try {
    const responses = await despia(url.toString().replace(/\+/g, '%20'), [watchKey]);
    const envelope = responses[watchKey];
    if (envelope === undefined) throw new Error('No native reply within 30 seconds');
    if (!envelope.ok) {
      const error = new Error('Native extension request failed');
      error.response = envelope;
      throw error;
    }
    return envelope.data;
  } finally {
    delete globalThis[callbackKey];
    delete globalThis[watchKey];
  }
}

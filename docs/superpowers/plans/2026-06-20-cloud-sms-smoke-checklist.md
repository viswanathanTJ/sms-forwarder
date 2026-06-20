# Cloud SMS — manual smoke test (two devices)

Pre-req: rules + indexes deployed, Cloud Function deployed, super-admin seeded,
`google-services.json` + `GOOGLE_WEB_CLIENT_ID` set, Auth providers enabled.

1. Device A (admin): sign in → device doc created with `publicKey`.
2. Device B (member): sign in with an allow-listed email → registered. A non-listed email is rejected ("not authorized").
3. Admin → Admin screen: Grant access (reader=B, source=A).
4. Device B → Watch: enable A, notify ON.
5. Device A: enable "Upload to cloud"; send an SMS to A.
6. Expect `inbox/{A-admin}/messages/{id}` and `inbox/{B}/messages/{id}` created; fields are ciphertext (unreadable in console).
7. Device B: gets a push with decrypted "sender: body"; Cloud SMS screen lists it.
8. Toggle notify OFF on B; send another SMS; B gets no push but sees it on opening the screen (snapshot listener).
9. Admin deletes a message → removed from all inboxes; a member has no Delete button and rules deny delete.
10. Admin revokes Device B → new SMS no longer fans out to B; B stops seeing new messages.

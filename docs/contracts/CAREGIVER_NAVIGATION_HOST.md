# Caregiver navigation host

`CaregiverNavigationHost` is the public Android seam for mounting C2/C3 destination views inside the C1 caregiver shell. It keeps the bottom navigation and current Elder header owned by C1 while feature teams own their page content and ViewModels.

## Contract

- Observe `navigationState` with the host lifecycle. It contains the selected destination and the current caregiver context.
- The Debug preview uses sample data and is never authorization. Feature pages must obtain their account and Elder scope from a trusted session and a server-confirmed active relationship.
- Mount a feature view with `mountDestinationContent(destination, view)`. The host displays that view only while its destination is selected; other destinations continue to use the shell's existing content.
- Call `clearDestinationContent(destination)` when the feature loses its account/Elder scope or its view is no longer valid.
- Use `navigateTo(destination)` for links inside a feature page so the shell selection and page state remain in sync.
- Use the supplied `fragmentManager` for child components that present dialogs. Do not retain an Activity or Elder identity beyond the host lifecycle/scope.

This contract mounts UI only. The current build does not yet wire a production session or PairingRepository. C2/C3 must bind their own reviewed feature ViewModels and clear them when the observed context changes or becomes unavailable. The server must still authorize every request.

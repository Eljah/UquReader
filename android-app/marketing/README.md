# Marketing assets

This directory collects auxiliary storefront graphics that must not be picked up
by the Android resource merger. During the Maven build the `play_store_512.png`
icon is automatically moved here if it is accidentally kept next to
`src/main/res`, which avoids `aapt` treating the misplaced file as a resource
directory. Store-related artwork can live here safely without breaking the
Android packaging step.

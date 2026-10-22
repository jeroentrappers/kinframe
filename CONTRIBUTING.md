# Contributing to kinframe

Thanks for helping. A few things to know before you open a pull request.

## Contributor License Agreement

kinframe is open source under the AGPL and is also offered under a commercial license (see "Licensing" in
`README.md`). To keep that possible, every contributor signs the [Contributor License Agreement](CLA.md) once.
You keep the copyright in your work; you give Appmire CommV the right to distribute it under the AGPL and under
commercial terms, and every contribution stays available as open source.

Signing takes one comment: when you open your first pull request, the CLA check asks you to reply with

> I have read the CLA Document and I hereby sign the CLA

The pull request can be merged once the check is green.

## Clean room

kinframe is a clean-room implementation. Do not contribute code, assets or text taken from Frameo apps or
firmware, decompiled or otherwise. Describe behaviour, not code.

## Before you open a pull request

- Follow the style of the code around your change.
- Add or update tests, and run the checks for the parts you touched (see the README of each component).
- User-visible text goes into the translation files for every supported language.
- No secrets or host-specific configuration in the repository.

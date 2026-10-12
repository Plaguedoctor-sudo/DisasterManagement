# F-Droid Submission Guide for DisasterManagement

This repository includes official metadata ready for submission to the **[F-Droid Official Data Repository (fdroiddata)](https://gitlab.com/fdroid/fdroiddata)**.

## Metadata File Location
* `fdroid/metadata/network.disaster.management.yml`

## Fast-Track Steps to Submit to F-Droid:

1. **Fork the Official F-Droid Repository:**
   * Go to [https://gitlab.com/fdroid/fdroiddata](https://gitlab.com/fdroid/fdroiddata) and click **Fork**.

2. **Add the Metadata File:**
   * In your fork, create a new branch: `git checkout -b add-disaster-management`
   * Copy the metadata file into the `metadata/` folder of your fork:
     ```bash
     cp fdroid/metadata/network.disaster.management.yml metadata/
     ```
   * Commit and push:
     ```bash
     git add metadata/network.disaster.management.yml
     git commit -m "Add network.disaster.management"
     git push origin add-disaster-management
     ```

3. **Open a Merge Request (MR):**
   * Visit [https://gitlab.com/fdroid/fdroiddata/-/merge_requests/new](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/new).
   * Set your branch as source, and `fdroiddata:master` as target.
   * In the MR title, use: `Add network.disaster.management (DisasterManagement)`
   * F-Droid's continuous integration bots will automatically run `fdroid checkupdates` and build test verification against tag `v1.2.0`.
   * Once merged by an F-Droid maintainer, the app will appear in the official F-Droid store client for all Android users worldwide!

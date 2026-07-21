package beer.parser.parsers;

import beer.parser.model.Brewery;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class BreweryLoader {

    public static List<Brewery> loadWhitelist(String filePath) {
        List<Brewery> whitelist = new ArrayList<>();

        try {
            List<String> lines = Files.readAllLines(Paths.get(filePath));

            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.trim().isEmpty()) continue;

                String[] columns = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");

                if (columns.length >= 2) {
                    String breweryName = columns[1].trim();

                    if (breweryName.startsWith("\"") && breweryName.endsWith("\"")) {
                        breweryName = breweryName.substring(1, breweryName.length() - 1);
                    }

                    whitelist.add(new Brewery(breweryName));
                }
            }
            System.out.println(">>> Успішно завантажено " + whitelist.size() + " топових броварень у Білий список.");

        } catch (Exception e) {
            System.err.println("Помилка під час читання файлу броварень: " + e.getMessage());
        }

        return whitelist;
    }
}